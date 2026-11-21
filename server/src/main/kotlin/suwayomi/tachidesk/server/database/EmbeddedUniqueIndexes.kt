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
        val persisted = mutableMapOf<String, MutableList<String>>()
        val invalid = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, ORDERING_SPECIFICATION, IS_UNIQUE
                FROM INFORMATION_SCHEMA.INDEX_COLUMNS
                WHERE INDEX_SCHEMA='PUBLIC' AND TABLE_SCHEMA='PUBLIC'
                ORDER BY INDEX_NAME, ORDINAL_POSITION
            """).use { rows ->
                while (rows.next()) {
                    val key = rows.getString(1) + "." + rows.getString(2)
                    persisted.getOrPut(key) { mutableListOf() }.add(rows.getString(3))
                    if (rows.getString(4) == "DESC" || rows.getBoolean(5)) invalid.add(key)
                }
            }
        }
        for ((table, columns) in indexes) for (column in columns) {
            val name = "IDX_${table}_${column.removeSuffix("_REF").removeSuffix("_ID")}"
            val key = "$table.$name"
            val fields = persisted[key].orEmpty()
            check(key !in invalid) { "Unexpected embedded lookup index $name" }
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
