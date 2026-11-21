package suwayomi.tachidesk.server.database

import java.sql.Connection

/** Keep references to a logically identical membership when M0062 retires its duplicate row ID. */
internal object EmbeddedDuplicateReferences {
    private fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""

    fun reconcileMemberships(connection: Connection) {
        val duplicates = "SELECT ID FROM CATEGORYMANGA WHERE ID NOT IN (SELECT MIN(ID) FROM CATEGORYMANGA GROUP BY CATEGORY, MANGA)"
        forEachDependent(connection, "CATEGORYMANGA", duplicates) { schema, table, column, referencesID ->
            check(referencesID && (schema != "PUBLIC" || table != "CATEGORYMANGA")) {
                "Cannot safely map this additional membership reference"
            }
            connection.metaData.getExportedKeys(null, schema, table).use { keys ->
                while (keys.next()) check(keys.getString("PKCOLUMN_NAME") != column) {
                    "Additional membership reference is a referenced key"
                }
            }
            val field = quoted(column)
            val child = "${quoted(schema)}.${quoted(table)}"
            connection.createStatement().use { sql ->
                sql.executeUpdate("""
                    UPDATE $child child SET $field=(
                        SELECT MIN(keeper.ID) FROM CATEGORYMANGA original
                        JOIN CATEGORYMANGA keeper ON keeper.CATEGORY=original.CATEGORY AND keeper.MANGA=original.MANGA
                        WHERE original.ID=child.$field)
                    WHERE child.$field IN ($duplicates)
                """.trimIndent())
            }
        }
    }

    fun verifyDeduplication(connection: Connection, table: String, group: List<String>, archived: Set<String> = emptySet()) {
        val keys = group.joinToString(", ", transform = ::quoted)
        val grouped = "SELECT original.*, MIN(ID) OVER (PARTITION BY $keys) AS GOMANGA_KEEPER_ID FROM $table original"
        val retired = "SELECT ID FROM ($grouped) g WHERE ID<>GOMANGA_KEEPER_ID"
        requireMapped(connection, table, retired)
        val extra = mutableListOf<String>()
        connection.metaData.getColumns(null, "PUBLIC", table, null).use { fields ->
            while (fields.next()) {
                val field = fields.getString("COLUMN_NAME")
                if (field != "ID" && field !in group && field !in archived) extra += quoted(field)
            }
        }
        if (extra.isEmpty()) return
        val different = extra.joinToString(" OR ") { "g.$it IS DISTINCT FROM keeper.$it" }
        connection.createStatement().use { sql ->
            sql.executeQuery("""
                SELECT 1 FROM ($grouped) g JOIN $table keeper ON keeper.ID=g.GOMANGA_KEEPER_ID
                WHERE g.ID<>keeper.ID AND ($different) FETCH FIRST ROW ONLY
            """.trimIndent()).use { check(!it.next()) { "Conflicting persisted fields on duplicate $table records" } }
        }
    }

    fun requireMapped(connection: Connection, parent: String, retired: String, knownColumns: Map<String, String> = emptyMap()) {
        forEachDependent(connection, parent, retired) { schema, table, column, referencesID ->
            check(referencesID && schema == "PUBLIC" && knownColumns[table] == column) {
                "An additional relationship to a retired $parent record requires a mapping"
            }
        }
    }

    private fun forEachDependent(
        connection: Connection, parent: String, retired: String, checkDependent: (String, String, String, Boolean) -> Unit,
    ) {
        data class Key(val schema: String, val table: String, val name: String)
        data class Column(val foreign: String, val referenced: String)
        val foreignKeys = mutableMapOf<Key, MutableList<Column>>()
        connection.metaData.getExportedKeys(null, "PUBLIC", parent).use { keys ->
            while (keys.next()) {
                val key = Key(keys.getString("FKTABLE_SCHEM"), keys.getString("FKTABLE_NAME"), keys.getString("FK_NAME"))
                foreignKeys.getOrPut(key) { mutableListOf() } += Column(keys.getString("FKCOLUMN_NAME"), keys.getString("PKCOLUMN_NAME"))
            }
        }
        for ((key, columns) in foreignKeys) {
            val join = columns.joinToString(" AND ") { "child.${quoted(it.foreign)}=original.${quoted(it.referenced)}" }
            val populated = connection.createStatement().use { sql ->
                sql.executeQuery("""
                    SELECT 1 FROM ${quoted(key.schema)}.${quoted(key.table)} child JOIN PUBLIC.${quoted(parent)} original ON $join
                    WHERE original.ID IN ($retired) FETCH FIRST ROW ONLY
                """.trimIndent()).use { it.next() }
            }
            if (populated) checkDependent(key.schema, key.table, columns.first().foreign, columns.singleOrNull()?.referenced == "ID")
        }
    }
}
