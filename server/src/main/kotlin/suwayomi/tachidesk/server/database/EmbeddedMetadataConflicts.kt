package suwayomi.tachidesk.server.database

import java.sql.Connection

/** Preserve the complete conflicting group before upstream M0049 keeps its lowest metadata row ID. */
internal object EmbeddedMetadataConflicts {
    fun captureChapterGroups(connection: Connection) {
        capture(connection, "CHAPTERMETA", "CHAPTER_REF",
            """SELECT m.*, (SELECT MIN(k.ID) FROM CHAPTER k WHERE k.MANGA=c.MANGA AND k.URL=c.URL) AS TARGET_OWNER
               FROM CHAPTERMETA m JOIN CHAPTER c ON c.ID=m.CHAPTER_REF""")
    }

    fun captureMetadataGroups(connection: Connection) {
        for ((table, owner) in mapOf(
            "CATEGORYMETA" to "CATEGORY_REF", "CHAPTERMETA" to "CHAPTER_REF",
            "GLOBALMETA" to null, "MANGAMETA" to "MANGA_REF", "SOURCEMETA" to "SOURCE_REF",
        )) {
            capture(connection, table, owner, "SELECT m.*, ${owner ?: "CAST(NULL AS BIGINT)"} AS TARGET_OWNER FROM $table m")
        }
    }

    private fun capture(connection: Connection, table: String, owner: String?, projection: String) {
        val rows = """
            SELECT m.*, MIN(ID) OVER (PARTITION BY TARGET_OWNER, "KEY") AS ACTIVE_ID,
                   MIN("VALUE") OVER (PARTITION BY TARGET_OWNER, "KEY") AS LOW_VALUE,
                   MAX("VALUE") OVER (PARTITION BY TARGET_OWNER, "KEY") AS HIGH_VALUE
            FROM ($projection) m
        """.trimIndent()
        connection.createStatement().use { sql ->
            val conflict = "SELECT * FROM ($rows) g WHERE LOW_VALUE <> HIGH_VALUE"
            if (!sql.executeQuery("SELECT 1 FROM ($conflict) FETCH FIRST ROW ONLY").use { it.next() }) return
            sql.executeUpdate("""
                CREATE TABLE IF NOT EXISTS GOMANGA_LEGACY_META_CONFLICT (
                    SOURCE_TABLE VARCHAR(32) NOT NULL, ORIGINAL_ID BIGINT NOT NULL,
                    ORIGINAL_OWNER BIGINT, TARGET_OWNER BIGINT, ACTIVE_ID BIGINT NOT NULL,
                    META_KEY VARCHAR NOT NULL, META_VALUE VARCHAR NOT NULL,
                    PRIMARY KEY (SOURCE_TABLE, ORIGINAL_ID))
            """)
            sql.executeUpdate("""
                INSERT INTO GOMANGA_LEGACY_META_CONFLICT
                    (SOURCE_TABLE, ORIGINAL_ID, ORIGINAL_OWNER, TARGET_OWNER, ACTIVE_ID, META_KEY, META_VALUE)
                SELECT '$table', g.ID, ${owner?.let { "g.$it" } ?: "NULL"}, g.TARGET_OWNER, g.ACTIVE_ID, g."KEY", g."VALUE"
                FROM ($conflict) g WHERE NOT EXISTS (
                    SELECT 1 FROM GOMANGA_LEGACY_META_CONFLICT a WHERE a.SOURCE_TABLE='$table' AND a.ORIGINAL_ID=g.ID)
            """)
        }
    }
}
