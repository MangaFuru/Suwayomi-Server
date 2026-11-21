package suwayomi.tachidesk.server.database

import de.neonew.exposed.migrations.Migration
import de.neonew.exposed.migrations.MigrationEntity
import de.neonew.exposed.migrations.MigrationsTable
import de.neonew.exposed.migrations.runMigrations
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.Types
import kotlin.time.Clock

/** Apply the exact missing known migrations, even when additional receipts exist. */
internal object EmbeddedSchemaRunner {
    fun preflight(connection: Connection, registrations: List<Migration>): Int {
        val prefix = EmbeddedMigrationLedger.completedPrefix(connection, registrations.map { it.name })
        check(prefix >= 59) { "Unsupported current-engine migration prefix" }
        // Required inputs of the historical projection, chapter progress and timestamp migrations.
        val expected = mapOf(
            "CHAPTER" to mapOf("ID" to Types.INTEGER, "MANGA" to Types.INTEGER, "LAST_PAGE_READ" to Types.INTEGER,
                "BOOKMARK" to Types.BOOLEAN, "DATE_UPLOAD" to Types.BIGINT),
            "EXTENSION" to mapOf("ID" to Types.INTEGER, "PKG_NAME" to Types.VARCHAR, "APK_NAME" to Types.VARCHAR,
                "VERSION_CODE" to Types.BIGINT, "IS_INSTALLED" to Types.BOOLEAN),
            "SOURCE" to mapOf("ID" to Types.BIGINT, "EXTENSION" to Types.INTEGER),
            "CATEGORYMANGA" to mapOf("ID" to Types.INTEGER, "MANGA" to Types.INTEGER, "CATEGORY" to Types.INTEGER),
        )
        for ((table, columns) in expected) {
            val actual = mutableMapOf<String, Int>()
            connection.metaData.getColumns(null, "PUBLIC", table, null).use { rows ->
                while (rows.next()) actual[rows.getString("COLUMN_NAME")] = rows.getInt("DATA_TYPE")
            }
            check(columns.all { (column, type) -> actual[column] == type }) { "Incompatible persisted $table column" }
        }
        EmbeddedUniqueIndexes.ensureUnique(connection, "EXTENSION", "UC_PKG_NAME", listOf("PKG_NAME"),
            verifyOnly = true, allowMissing = prefix < 60)
        EmbeddedUniqueIndexes.ensureLookupIndexes(connection, verifyOnly = true, allowMissing = prefix < 61)
        EmbeddedUniqueIndexes.ensureUnique(connection, "CATEGORYMANGA", "UC_CATEGORYMANGA", listOf("MANGA", "CATEGORY"),
            verifyOnly = true, allowMissing = prefix < 62)
        EmbeddedSyncFlagColumns.ensure(connection, requireComplete = prefix >= 64, inspectOnly = true)
        if (prefix >= 66) EmbeddedSyncTriggers.reconcile(connection, verifyOnly = true)
        return prefix
    }

    fun upgrade(
        database: Database,
        registrations: List<Migration>,
        beforeMigration: (Migration, Connection) -> Unit,
    ) {
        val ordered = registrations.sortedBy { it.version }
        require(ordered.isNotEmpty() && ordered.map { it.version } == (1..ordered.size).toList()) {
            "Embedded migration registration is incomplete"
        }
        val expectedNames = ordered.map { it.name }
        val prior =
            transaction(database) {
                EmbeddedMigrationLedger.completedPrefix(connection.connection as Connection, expectedNames)
            }

        if (prior < ordered.size) {
            EmbeddedDatabaseUpgradeProgress.migratingSchema()
            // Initialize the library's SQL name resolver using the existing
            // ledger. The empty list delegates setup without applying a step.
            runMigrations(emptyList(), database)
            for (migration in ordered.drop(prior)) {
                transaction(database) {
                    maxAttempts = 1
                    val connection = connection.connection as Connection
                    when (migration.version) {
                        39 -> EmbeddedChapterNormalization.beforeDuplicateRemoval(connection)
                        45 -> EmbeddedDuplicateReferences.verifyDeduplication(connection, "PAGE", listOf("INDEX", "imageUrl", "CHAPTER"))
                        49 -> EmbeddedMetadataConflicts.captureMetadataGroups(connection)
                    }
                    beforeMigration(migration, connection)
                    when (migration.version) {
                        60 -> EmbeddedUniqueIndexes.ensureUnique(connection, "EXTENSION", "UC_PKG_NAME", listOf("PKG_NAME"))
                        61 -> EmbeddedUniqueIndexes.ensureLookupIndexes(connection)
                        62 -> {
                            EmbeddedProjectionNormalization.categoryMembership(connection)
                            EmbeddedUniqueIndexes.ensureUnique(connection, "CATEGORYMANGA", "UC_CATEGORYMANGA", listOf("MANGA", "CATEGORY"))
                        }
                        64 -> EmbeddedSyncFlagColumns.ensure(connection)
                        else -> migration.run()
                    }
                    MigrationEntity.new {
                        version = EntityID(migration.version, MigrationsTable)
                        name = migration.name
                        executedAt = Clock.System.now()
                    }
                }
            }
        }

        transaction(database) {
            val connection = connection.connection as Connection
            check(EmbeddedMigrationLedger.completedPrefix(connection, expectedNames) == ordered.size) {
                "Embedded database migration receipts are incomplete"
            }
            if (ordered.size >= 62) {
                EmbeddedUniqueIndexes.ensureUnique(connection, "EXTENSION", "UC_PKG_NAME", listOf("PKG_NAME"), verifyOnly = true)
                EmbeddedUniqueIndexes.ensureLookupIndexes(connection, verifyOnly = true)
                EmbeddedUniqueIndexes.ensureUnique(connection, "CATEGORYMANGA", "UC_CATEGORYMANGA", listOf("MANGA", "CATEGORY"), verifyOnly = true)
            }
            if (ordered.size >= 66) EmbeddedSyncTriggers.reconcile(connection, verifyOnly = true)
            if (ordered.size >= 64) {
                EmbeddedSyncFlagColumns.ensure(connection, requireComplete = true)
            }
        }
    }
}
