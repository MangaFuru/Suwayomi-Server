package suwayomi.tachidesk.server.database

import java.sql.Connection
import java.sql.Types

/** Resolve the known migration prefix using the receipts themselves. */
internal object EmbeddedMigrationLedger {
    private val requiredColumns =
        mapOf(
            "VERSION" to Types.INTEGER,
            "NAME" to Types.VARCHAR,
            "EXECUTED_AT" to Types.TIMESTAMP,
        )

    /**
     * Returns the number of consecutive known migrations already applied.
     * Additional future receipts are retained and do not skip known pending work.
     */
    fun completedPrefix(
        connection: Connection,
        expectedNames: List<String>,
    ): Int {
        val metadata = connection.metaData
        val columns = mutableMapOf<String, Int>()
        metadata.getColumns(null, "PUBLIC", "MIGRATIONS", null).use { rows ->
            while (rows.next()) {
                columns[rows.getString("COLUMN_NAME")] = rows.getInt("DATA_TYPE")
            }
        }
        check(requiredColumns.all { (column, type) -> columns[column] == type }) {
            "Incompatible embedded migration ledger"
        }

        var prefix = 0
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT VERSION, NAME FROM PUBLIC.MIGRATIONS ORDER BY VERSION").use { rows ->
                while (rows.next()) {
                    val version = rows.getInt("VERSION")
                    check(version > 0) { "Invalid embedded migration receipt" }
                    val name = rows.getString("NAME")
                    if (version > expectedNames.size) {
                        check(name !in expectedNames) { "Embedded migration receipt is registered at an incompatible version" }
                        continue
                    }
                    check(version == prefix + 1 && name == expectedNames[version - 1]) {
                        "Embedded migration receipts do not match a known prefix"
                    }
                    prefix = version
                }
            }
        }
        return prefix
    }
}
