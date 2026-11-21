package suwayomi.tachidesk.server.database

import java.sql.Connection

/** Preserve state and dependents before historical M0039 deletes duplicate chapters. */
internal object EmbeddedChapterNormalization {
    private const val sameChapter = "original.URL = keeper.URL AND original.MANGA = keeper.MANGA"
    private val mergedColumns = setOf(
        "ID", "URL", "MANGA", "READ", "BOOKMARK", "LAST_PAGE_READ", "LAST_READ_AT",
        "IS_DOWNLOADED", "PAGE_COUNT",
    )

    fun beforeDuplicateRemoval(connection: Connection) {
        connection.createStatement().use { sql ->
            if (!sql.executeQuery("SELECT 1 FROM CHAPTER GROUP BY MANGA, URL HAVING COUNT(*) > 1").use { it.next() }) {
                return
            }
            val additionalColumns = mutableListOf<String>()
            connection.metaData.getColumns(null, "PUBLIC", "CHAPTER", null).use { columns ->
                while (columns.next()) {
                    val name = columns.getString("COLUMN_NAME")
                    if (name !in mergedColumns) additionalColumns += quote(name)
                }
            }
            if (additionalColumns.isNotEmpty()) {
                val differences = additionalColumns.joinToString(" OR ") { "original.$it IS DISTINCT FROM keeper.$it" }
                sql.executeQuery(
                    """
                    SELECT 1 FROM CHAPTER original
                    JOIN CHAPTER keeper ON $sameChapter AND original.ID > keeper.ID
                    WHERE $differences FETCH FIRST ROW ONLY
                    """.trimIndent(),
                ).use { check(!it.next()) { "Conflicting persisted fields on duplicate legacy chapters" } }
            }

            EmbeddedDuplicateReferences.requireMapped(connection, "CHAPTER",
                "SELECT original.ID FROM CHAPTER original JOIN CHAPTER keeper ON $sameChapter AND original.ID > keeper.ID",
                mapOf("PAGE" to "CHAPTER", "CHAPTERMETA" to "CHAPTER_REF"))
            EmbeddedMetadataConflicts.captureChapterGroups(connection)

            sql.executeUpdate(
                """
                UPDATE CHAPTER keeper
                SET ("READ", BOOKMARK, LAST_PAGE_READ, LAST_READ_AT, IS_DOWNLOADED, PAGE_COUNT) =
                    (SELECT BOOL_OR(original."READ"), BOOL_OR(original.BOOKMARK), GREATEST(0, MAX(original.LAST_PAGE_READ)),
                            MAX(original.LAST_READ_AT), BOOL_OR(original.IS_DOWNLOADED), MAX(original.PAGE_COUNT)
                    FROM CHAPTER original WHERE $sameChapter)
                WHERE EXISTS (SELECT 1 FROM CHAPTER original WHERE $sameChapter AND original.ID > keeper.ID)
                AND NOT EXISTS (SELECT 1 FROM CHAPTER original WHERE $sameChapter AND original.ID < keeper.ID)
                """.trimIndent(),
            )
            for ((table, chapterKey) in listOf("CHAPTERMETA" to "CHAPTER_REF", "PAGE" to "CHAPTER")) {
                sql.executeUpdate(
                    """
                    UPDATE $table dependent
                    SET $chapterKey =
                        (SELECT MIN(keeper.ID) FROM CHAPTER original
                        JOIN CHAPTER keeper ON $sameChapter WHERE original.ID = dependent.$chapterKey)
                    WHERE EXISTS
                        (SELECT 1 FROM CHAPTER original JOIN CHAPTER keeper ON $sameChapter AND keeper.ID < original.ID
                        WHERE original.ID = dependent.$chapterKey)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
}
