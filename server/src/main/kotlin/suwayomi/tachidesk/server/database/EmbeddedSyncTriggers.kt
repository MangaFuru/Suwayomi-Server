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

    private data class ChapterTrigger(
        val name: String,
        val table: String,
        val timing: String,
        val event: String,
        val handler: String?,
        val orientation: String?,
    )

    /**
     * M0065 changes only chapter.date_upload. For a known H2 catalog, remove only the two
     * shipped chapter UPDATE triggers for the one bulk UPDATE, and restore them in M0066 before promotion. Unknown chapter UPDATE triggers deliberately take
     * the ordinary whole-UPDATE path so extension/user behavior is unchanged.
     */
    fun <T> withChapterTimestampMigration(connection: Connection, action: () -> T): T {
        if (connection.metaData.databaseProductName != "H2" || !hasExactChapterTimestampCatalog(connection)) {
            return action()
        }
        connection.createStatement().use { statement ->
            statement.execute("DROP TRIGGER PUBLIC.UPDATE_CHAPTER_AND_MANGA_VERSION")
            statement.execute("DROP TRIGGER PUBLIC.UPDATE_CHAPTER_LAST_MODIFIED_AT")
        }
        // M0066 restores the owned definitions before the private file can be promoted.
        return action()
    }

    private fun hasExactChapterTimestampCatalog(connection: Connection): Boolean {
        val expected = setOf(
            ChapterTrigger(
                "UPDATE_CHAPTER_AND_MANGA_VERSION", "CHAPTER", "BEFORE", "UPDATE",
                "suwayomi.tachidesk.server.database.trigger.UpdateChapterAndMangaVersionTrigger", "ROW",
            ),
            ChapterTrigger(
                "UPDATE_CHAPTER_LAST_MODIFIED_AT", "CHAPTER", "BEFORE", "UPDATE",
                "suwayomi.tachidesk.server.database.trigger.UpdateChapterLastModifiedAtTrigger", "ROW",
            ),
        )
        val catalog = mutableListOf<ChapterTrigger>()
        connection.prepareStatement("""
            SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION, JAVA_CLASS, ACTION_ORIENTATION
            FROM INFORMATION_SCHEMA.TRIGGERS
            WHERE TRIGGER_SCHEMA='PUBLIC' AND EVENT_OBJECT_TABLE='CHAPTER'
        """).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    catalog += ChapterTrigger(
                        rows.getString("TRIGGER_NAME"), rows.getString("EVENT_OBJECT_TABLE"),
                        rows.getString("ACTION_TIMING"), rows.getString("EVENT_MANIPULATION"),
                        rows.getString("JAVA_CLASS"), rows.getString("ACTION_ORIENTATION"),
                    )
                }
            }
        }
        val ownedNames = expected.map { it.name }.toSet()
        val owned = catalog.filter { it.name in ownedNames }.toSet()
        val unknownUpdate = catalog.any { it.event == "UPDATE" && it !in expected }
        return owned == expected && !unknownUpdate
    }

    fun reconcile(connection: Connection, verifyOnly: Boolean = false) {
        val persisted = mutableMapOf<String, MutableSet<String>>()
        val invalid = mutableSetOf<String>()
        val expected = required.associateBy { it.name }
        connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION, JAVA_CLASS, ACTION_ORIENTATION
                FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA='PUBLIC'
            """).use { rows ->
                while (rows.next()) {
                    val spec = expected[rows.getString(1)] ?: continue
                    persisted.getOrPut(spec.name) { mutableSetOf() }.add(rows.getString(4))
                    if (rows.getString(2) != spec.table || rows.getString(3) != spec.timing ||
                        rows.getString(5) != spec.handler || rows.getString(6) != "ROW") invalid.add(spec.name)
                }
            }
        }
        for (spec in required) {
            val events = persisted[spec.name].orEmpty()
            check(events.all { it in spec.events }) {
                "Unknown event on embedded trigger ${spec.name}"
            }
            if (spec.name !in invalid && events == spec.events) continue
            check(!verifyOnly) { "Incomplete embedded trigger ${spec.name}" }
            connection.createStatement().use { sql ->
                if (events.isNotEmpty()) sql.execute("DROP TRIGGER PUBLIC.${spec.name}")
                sql.execute("""CREATE TRIGGER PUBLIC.${spec.name} ${spec.timing} ${spec.events.joinToString()}
                    ON PUBLIC.${spec.table} FOR EACH ROW CALL "${spec.handler}" """)
            }
        }
    }
}
