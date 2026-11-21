package suwayomi.tachidesk.server.database

import java.sql.Connection

/** Preserve source identity and membership when v2.4 adds unique constraints. */
internal object EmbeddedProjectionNormalization {
    private val extensionProjection =
        listOf(
            "APK_NAME", "ICON_URL", "NAME", "VERSION_NAME", "VERSION_CODE", "LANG",
            "IS_INSTALLED", "HAS_UPDATE", "IS_OBSOLETE", "CLASS_NAME", "STORE_INDEX_URL",
            "EXTENSION_LIB", "APK_URL", "CONTENT_WARNING", "JAR_URL",
        )

    fun extensionPackages(
        connection: Connection,
        isInstalledArtifact: (packageName: String, apkName: String, versionCode: Long) -> Boolean,
    ) {
        val packages =
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT PKG_NAME FROM EXTENSION GROUP BY PKG_NAME HAVING COUNT(*) > 1").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        if (packages.isEmpty()) return

        connection.metaData.getExportedKeys(null, "PUBLIC", "EXTENSION").use { keys ->
            while (keys.next()) {
                val schema = keys.getString("FKTABLE_SCHEM")
                val table = keys.getString("FKTABLE_NAME")
                val foreignKey = keys.getString("FKCOLUMN_NAME")
                val key = keys.getString("PKCOLUMN_NAME")
                if (schema == "PUBLIC" && table == "SOURCE" && foreignKey == "EXTENSION" && key == "ID") continue
                connection.createStatement().use { sql ->
                    sql.executeQuery(
                        """
                        SELECT 1 FROM ${quote(schema)}.${quote(table)}
                        WHERE ${quote(foreignKey)} IN (
                            SELECT original.${quote(key)} FROM EXTENSION original
                            WHERE EXISTS (SELECT 1 FROM EXTENSION stable
                                WHERE stable.PKG_NAME=original.PKG_NAME AND stable.ID < original.ID)
                        ) FETCH FIRST ROW ONLY
                        """.trimIndent(),
                    ).use { check(!it.next()) { "Cannot safely reconcile additional extension references" } }
                }
            }
        }
        val existing = columns(connection, "EXTENSION")
        check(existing.containsAll(extensionProjection)) {
            "Extension projection is missing required columns"
        }
        val extra = existing - extensionProjection.toSet() - setOf("ID", "PKG_NAME")
        if (extra.isNotEmpty()) {
            val differences = extra.joinToString(" OR ") { name ->
                val column = quote(name)
                "original.$column IS DISTINCT FROM stable.$column"
            }
            connection.createStatement().use { sql ->
                sql.executeQuery(
                    """
                    SELECT 1 FROM EXTENSION original JOIN EXTENSION stable
                    ON original.PKG_NAME = stable.PKG_NAME AND original.ID > stable.ID
                    WHERE $differences FETCH FIRST ROW ONLY
                    """.trimIndent(),
                ).use { check(!it.next()) { "Conflicting additional data in duplicate extension records" } }
            }
        }

        for (pkg in packages) {
            val candidates =
                connection.prepareStatement(
                    """
                    SELECT ID, APK_NAME, VERSION_CODE, IS_INSTALLED FROM EXTENSION WHERE PKG_NAME = ?
                    ORDER BY IS_INSTALLED DESC, VERSION_CODE DESC, ID
                    """.trimIndent(),
                ).use { query ->
                    query.setString(1, pkg)
                    query.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(ExtensionRow(rows.getInt(1), rows.getString(2), rows.getLong(3), rows.getBoolean(4)))
                            }
                        }
                    }
                }
            val stable = candidates.minOf { it.id }
            val choice = candidates.firstOrNull { candidate ->
                candidate.apkName?.let { isInstalledArtifact(pkg, it, candidate.versionCode) } == true
            } ?: candidates.first()
            val projected = extensionProjection.joinToString(", ")
            connection.prepareStatement(
                "UPDATE EXTENSION SET ($projected) = (SELECT $projected FROM EXTENSION WHERE ID=?) WHERE ID=?",
            ).use { update ->
                update.setInt(1, choice.id)
                update.setInt(2, stable)
                check(update.executeUpdate() == 1) { "Stable extension projection disappeared" }
            }
            connection.prepareStatement("UPDATE EXTENSION SET IS_INSTALLED=? WHERE ID=?").use { update ->
                update.setBoolean(1, candidates.any { it.installed })
                update.setInt(2, stable)
                update.executeUpdate()
            }
            connection.prepareStatement("UPDATE SOURCE SET EXTENSION=? WHERE EXTENSION=?").use { move ->
                connection.prepareStatement("DELETE FROM EXTENSION WHERE ID=?").use { remove ->
                    for (row in candidates) {
                        if (row.id == stable) continue
                        move.setInt(1, stable)
                        move.setInt(2, row.id)
                        move.executeUpdate()
                        remove.setInt(1, row.id)
                        remove.executeUpdate()
                    }
                }
            }
        }
    }

    fun categoryMembership(connection: Connection) {
        connection.createStatement().use { sql ->
            val duplicate = sql.executeQuery(
                "SELECT 1 FROM CATEGORYMANGA GROUP BY MANGA, CATEGORY HAVING COUNT(*) > 1",
            ).use { it.next() }
            if (!duplicate) return
            check(columns(connection, "CATEGORYMANGA") == setOf("ID", "MANGA", "CATEGORY")) {
                "Cannot collapse duplicate memberships with additional columns"
            }
            sql.executeUpdate(
                "DELETE FROM CATEGORYMANGA WHERE ID NOT IN (SELECT MIN(ID) FROM CATEGORYMANGA GROUP BY MANGA, CATEGORY)",
            )
        }
    }

    private fun columns(connection: Connection, table: String): Set<String> =
        connection.metaData.getColumns(null, "PUBLIC", table, null).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("COLUMN_NAME")) }
        }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private data class ExtensionRow(val id: Int, val apkName: String?, val versionCode: Long, val installed: Boolean)
}
