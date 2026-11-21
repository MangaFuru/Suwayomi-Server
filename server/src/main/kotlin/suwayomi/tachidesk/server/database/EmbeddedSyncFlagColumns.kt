package suwayomi.tachidesk.server.database

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Types

/** H2 can commit each flag-column DDL before its migration receipt. */
internal object EmbeddedSyncFlagColumns {
    private data class FlagColumn(val table: String, val name: String, val nullable: Boolean) {
        val definition: String
            get() = if (nullable) "INT NULL" else "INT NOT NULL DEFAULT 0"
    }

    private val columns =
        listOf(
            FlagColumn("CATEGORY", "FLAGS", nullable = false),
            FlagColumn("MANGA", "VIEWER", nullable = false),
            FlagColumn("MANGA", "VIEWER_FLAGS", nullable = true),
            FlagColumn("MANGA", "CHAPTER_FLAGS", nullable = false),
        )

    fun ensure(connection: Connection, requireComplete: Boolean = false, inspectOnly: Boolean = false) {
        val metadata = connection.metaData
        for (table in columns.map { it.table }.distinct()) {
            metadata.getTables(null, "PUBLIC", table, null).use { rows ->
                check(rows.next()) { "Required sync flag table is missing" }
            }
        }
        val missing = columns.filterNot { hasCompatibleColumn(metadata, it) }
        if (requireComplete) {
            check(missing.isEmpty()) { "Embedded sync flag migration is incomplete" }
            return
        }
        if (inspectOnly || missing.isEmpty()) return

        connection.createStatement().use { statement ->
            for (column in missing) {
                statement.executeUpdate(
                    "ALTER TABLE PUBLIC.${column.table} ADD COLUMN ${column.name} ${column.definition}",
                )
            }
        }
        ensure(connection, requireComplete = true)
    }

    private fun hasCompatibleColumn(metadata: DatabaseMetaData, column: FlagColumn): Boolean =
        // getColumns uses SQL patterns, so underscore in VIEWER_FLAGS is a wildcard.
        metadata.getColumns(null, "PUBLIC", column.table, null).use { rows ->
            while (rows.next()) {
                if (rows.getString("COLUMN_NAME") != column.name) continue
                val expectedNullability =
                    if (column.nullable) DatabaseMetaData.columnNullable else DatabaseMetaData.columnNoNulls
                val defaultValue = rows.getString("COLUMN_DEF")
                val compatibleDefault =
                    if (column.nullable) defaultValue == null || defaultValue.equals("NULL", ignoreCase = true) else defaultValue == "0"
                check(
                    rows.getInt("DATA_TYPE") == Types.INTEGER &&
                        rows.getInt("NULLABLE") == expectedNullability &&
                        compatibleDefault,
                ) {
                    "Incompatible persisted sync flag definition"
                }
                return@use true
            }
            false
        }
}
