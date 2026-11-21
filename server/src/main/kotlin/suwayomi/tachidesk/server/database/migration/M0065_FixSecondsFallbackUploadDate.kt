package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

// New chapters without an upload date fell back to the fetch time in seconds instead of
// milliseconds. 10^11 ms is March 1973 while 10^11 s is the year 5138, so every value in
// that range is a seconds timestamp. date_upload is not a synced column, so the SyncYomi
// version and last_modified_at triggers don't fire.
@Suppress("ClassName", "unused")
class M0065_FixSecondsFallbackUploadDate : SQLMigration() {
    override val sql: String = when (serverConfig.databaseType.value) {
        DatabaseType.POSTGRESQL -> postgresQuery()
        DatabaseType.H2 -> h2Query()
    }

    // language=postgresql
    private fun postgresQuery(): String =
        """
        UPDATE chapter
        SET date_upload = date_upload * 1000
        WHERE date_upload > 0 AND date_upload < 100000000000;
        """.trimIndent()

    // date_upload is not watched by either chapter UPDATE trigger. Temporarily removing only the
    // two triggers owned by M0056/M0063 avoids invoking a Kotlin TriggerAdapter for every row in
    // a large legacy database. M0066 reconciles these exact definitions after this migration;
    // unknown/user-defined triggers are not addressed.
    private fun h2Query(): String =
        """
        DROP TRIGGER IF EXISTS PUBLIC.UPDATE_CHAPTER_AND_MANGA_VERSION;
        DROP TRIGGER IF EXISTS PUBLIC.UPDATE_CHAPTER_LAST_MODIFIED_AT;
        UPDATE PUBLIC.CHAPTER
        SET DATE_UPLOAD = DATE_UPLOAD * 1000
        WHERE DATE_UPLOAD > 0 AND DATE_UPLOAD < 100000000000;
        CREATE TRIGGER PUBLIC.UPDATE_CHAPTER_AND_MANGA_VERSION BEFORE UPDATE ON PUBLIC.CHAPTER
            FOR EACH ROW CALL "suwayomi.tachidesk.server.database.trigger.UpdateChapterAndMangaVersionTrigger";
        CREATE TRIGGER PUBLIC.UPDATE_CHAPTER_LAST_MODIFIED_AT BEFORE UPDATE ON PUBLIC.CHAPTER
            FOR EACH ROW CALL "suwayomi.tachidesk.server.database.trigger.UpdateChapterLastModifiedAtTrigger";
        """.trimIndent()
}
