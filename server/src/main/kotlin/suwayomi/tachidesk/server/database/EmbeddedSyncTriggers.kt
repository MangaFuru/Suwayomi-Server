package suwayomi.tachidesk.server.database

import java.sql.Connection

/** Reconcile only trigger names owned by the shipped H2 migrations. */
internal object EmbeddedSyncTriggers {
    private data class Spec(val name: String, val table: String, val timing: String, val events: Set<String>, val handler: String)
    private fun spec(name: String, table: String, timing: String, events: String, handler: String) =
        Spec(name, table, timing, events.split(",").toSet(), "suwayomi.tachidesk.server.database.trigger.${handler}Trigger")

    private val required = listOf(
        spec("UPDATE_MANGA_VERSION", "MANGA", "BEFORE", "UPDATE", "UpdateMangaVersion"),
        spec("UPDATE_CHAPTER_AND_MANGA_VERSION", "CHAPTER", "BEFORE", "UPDATE", "UpdateChapterAndMangaVersion"),
        spec("UPDATE_MANGA_LAST_MODIFIED_AT", "MANGA", "BEFORE", "UPDATE", "UpdateMangaLastModifiedAt"),
        spec("INSERT_MANGA_LAST_MODIFIED_AT", "MANGA", "BEFORE", "INSERT", "UpdateMangaLastModifiedAt"),
        spec("UPDATE_CHAPTER_LAST_MODIFIED_AT", "CHAPTER", "BEFORE", "UPDATE", "UpdateChapterLastModifiedAt"),
        spec("INSERT_CHAPTER_LAST_MODIFIED_AT", "CHAPTER", "BEFORE", "INSERT", "UpdateChapterLastModifiedAt"),
        spec("INSERT_MANGA_CATEGORY_UPDATE_VERSION", "CATEGORYMANGA", "AFTER", "INSERT", "InsertMangaCategoryUpdateVersion"),
        spec("DELETE_MANGA_CATEGORY_UPDATE_VERSION", "CATEGORYMANGA", "AFTER", "DELETE", "DeleteMangaCategoryUpdateVersion"),
        spec("TRACKRECORD_UPDATE_MANGA_VERSION", "TRACKRECORD", "AFTER", "INSERT,UPDATE,DELETE", "TrackRecordUpdateMangaVersion"),
        spec("INSERT_CATEGORY_UID", "CATEGORY", "BEFORE", "INSERT", "InsertCategoryUid"),
        spec("UPDATE_CATEGORY_VERSION", "CATEGORY", "BEFORE", "UPDATE", "UpdateCategoryVersion"),
    )

    fun reconcile(connection: Connection, verifyOnly: Boolean = false) {
        for (spec in required) {
            val events = mutableSetOf<String>()
            var definitionMatches = true
            connection.prepareStatement("""
                SELECT EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION, JAVA_CLASS, ACTION_ORIENTATION
                FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA='PUBLIC' AND TRIGGER_NAME=?
            """).use { statement ->
                statement.setString(1, spec.name)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        events += rows.getString(3)
                        definitionMatches = definitionMatches && rows.getString(1) == spec.table &&
                            rows.getString(2) == spec.timing && rows.getString(4) == spec.handler && rows.getString(5) == "ROW"
                    }
                }
            }
            if (definitionMatches && events == spec.events) continue
            check(!verifyOnly) { "Incomplete embedded trigger ${spec.name}" }
            connection.createStatement().use { sql ->
                if (events.isNotEmpty()) sql.execute("DROP TRIGGER PUBLIC.${spec.name}")
                sql.execute("""CREATE TRIGGER PUBLIC.${spec.name} ${spec.timing} ${spec.events.joinToString()}
                    ON PUBLIC.${spec.table} FOR EACH ROW CALL "${spec.handler}" """)
            }
        }
    }
}
