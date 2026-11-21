package suwayomi.tachidesk.server.database

import java.sql.Connection

/** H2 commits index and constraint DDL independently of the migration receipt. */
internal object EmbeddedUniqueIndexes {
    private val indexes = mapOf(
        "CATEGORYMANGA" to listOf("CATEGORY", "MANGA"),
        "CATEGORYMETA" to listOf("CATEGORY_REF"),
        "CHAPTER" to listOf("MANGA"),
        "CHAPTERMETA" to listOf("CHAPTER_REF"),
        "MANGAMETA" to listOf("MANGA_REF"),
        "PAGE" to listOf("CHAPTER"),
        "SOURCE" to listOf("EXTENSION"),
        "SOURCEMETA" to listOf("SOURCE_REF"),
        "TRACKRECORD" to listOf("MANGA_ID"),
    )

    fun ensureLookupIndexes(connection: Connection, verifyOnly: Boolean = false, allowMissing: Boolean = false) {
        for ((table, columns) in indexes) for (column in columns) {
            val name = "IDX_${table}_${column.removeSuffix("_REF").removeSuffix("_ID")}"
            val fields = mutableListOf<String>()
            connection.metaData.getIndexInfo(null, "PUBLIC", table, false, false).use { rows ->
                while (rows.next()) if (rows.getString("INDEX_NAME") == name) {
                    check(rows.getBoolean("NON_UNIQUE") && rows.getString("ASC_OR_DESC") != "D")
                    fields += rows.getString("COLUMN_NAME")
                }
            }
            if (fields.isEmpty() && allowMissing) continue
            if (fields.isEmpty() && !verifyOnly) {
                connection.createStatement().use { it.execute("CREATE INDEX $name ON $table ($column)") }
            } else {
                check(fields == listOf(column)) { "Unexpected embedded lookup index $name" }
            }
        }
    }

    fun ensureUnique(connection: Connection, table: String, name: String, columns: List<String>, verifyOnly: Boolean = false, allowMissing: Boolean = false) {
        val query = """
            SELECT c.TABLE_NAME,c.CONSTRAINT_TYPE,k.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c
            LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k ON k.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA
                AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME
            WHERE c.CONSTRAINT_SCHEMA='PUBLIC' AND c.CONSTRAINT_NAME=? ORDER BY k.ORDINAL_POSITION
        """
        val fields = connection.prepareStatement(query).use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    check(rows.getString(1) == table && rows.getString(2) == "UNIQUE") { "Unexpected constraint $name" }
                    add(rows.getString(3))
                }
            } }
        }
        if (fields.isEmpty() && allowMissing) return
        if (fields.isEmpty() && !verifyOnly) {
            connection.createStatement().use { it.execute("ALTER TABLE $table ADD CONSTRAINT $name UNIQUE (${columns.joinToString()})") }
        } else {
            check(fields == columns) { "Unexpected embedded unique key $name" }
        }
    }
}
