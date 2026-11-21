package suwayomi.tachidesk.server.database

import de.neonew.exposed.migrations.Migration
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.migration.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties
import org.h2.Driver

internal object EmbeddedDatabaseAdmission {
    @OptIn(ExperimentalKeywordApi::class)
    fun prepare(dirs: ApplicationDirs, legacyDriver: Path = packagedLegacyDriver()) {
        val file = Path.of(dirs.dataRoot, "database.mv.db")
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Expected existing database or packaged seed" }
        val classes = registrations()
        val names = classes.mapIndexed { index, type ->
            val name = type.simpleName
            check(name.substringBefore('_').removePrefix("M").toInt() == index + 1)
            name.substringAfter('_')
        }
        val extensions = Path.of(dirs.extensionsRoot)

        fun migrate(path: Path) {
            require(';' !in path.toString())
            val database = Database.connect(
                "jdbc:h2:${path.toAbsolutePath().toString().removeSuffix(".mv.db")};IFEXISTS=TRUE",
                driver = "org.h2.Driver", user = "", password = "",
                databaseConfig = DatabaseConfig { preserveKeywordCasing = false },
            )
            try {
                val migrations = classes.map { it.getDeclaredConstructor().newInstance() }
                EmbeddedSchemaRunner.upgrade(database, migrations) { step, connection ->
                    if (step.version == 60) EmbeddedProjectionNormalization.extensionPackages(connection) { pkg, apk, version ->
                        val jar = extensions.resolve(apk.substringBeforeLast('.') + ".jar").normalize()
                        if (jar.parent != extensions || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) false
                        else Extension.extractAndParseAndroidManifest(jar).let { it.packageName == pkg && it.versionCode?.toLong() == version }
                    }
                }
            } finally {
                TransactionManager.closeAndUnregister(database)
            }
        }

        fun fileFormat(): String? {
            val header = Files.newInputStream(file).use { String(it.readNBytes(512), Charsets.US_ASCII).substringBefore('\n') }
            return header.split(',').singleOrNull { it.startsWith("format:") }
        }
        val lockFile = file.resolveSibling(".gomanga-h2-conversion.lock")
        fun withConversionLock(action: () -> Unit) {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                checkNotNull(channel.tryLock()) { "Embedded database conversion is already active" }.use {
                    action()
                    EmbeddedLegacyH2.removeAbandonedWorkspaces(file)
                }
            }
        }
        fun inspect(path: Path, readOnly: Boolean): Int {
            val database = path.toAbsolutePath().toString().removeSuffix(".mv.db")
            require(';' !in database)
            val properties = Properties().apply {
                setProperty("user", "")
                setProperty("password", "")
            }
            val mode = if (readOnly) ";ACCESS_MODE_DATA=r" else ""
            return Driver().connect("jdbc:h2:$database;IFEXISTS=TRUE;TRACE_LEVEL_FILE=0$mode", properties).use {
                EmbeddedSchemaRunner.preflight(it, names)
            }
        }
        fun recover() = EmbeddedLegacyH2.recoverCurrent(file) { staged ->
            if (inspect(staged, readOnly = false) < classes.size) migrate(staged)
            check(inspect(staged, readOnly = true) == classes.size)
        }
        fun admit(locked: Boolean) {
            when (fileFormat()) {
                "format:3" -> {
                    val completed = try {
                        inspect(file, readOnly = true)
                    } catch (error: java.sql.SQLException) {
                        val needsRecovery = generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<org.h2.mvstore.MVStoreException>()
                            .any { it.message?.startsWith("This store is read-only [") == true }
                        if (!needsRecovery) throw error
                        if (locked) recover() else withConversionLock(::recover)
                        return
                    }
                    if (completed < classes.size) migrate(file)
                }
                "format:1" -> {
                    EmbeddedDatabaseUpgradeProgress.convertingFormat()
                    val budget = Math.addExact(Math.multiplyExact(Files.size(file), 4L), 32L * 1024 * 1024)
                    EmbeddedLegacyH2.convert(file, legacyDriver, budget, null, names, ::migrate)
                }
                else -> error("Unsupported embedded database file format")
            }
        }

        if (fileFormat() == "format:1" || Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            withConversionLock { admit(locked = true) }
        } else {
            admit(locked = false)
        }
    }

    // The iOS classpath may split migration classes across two JARs.
    private fun registrations(): List<Class<out Migration>> = listOf(
        M0001_Initial::class.java,
        M0002_ChapterTableIndexRename::class.java,
        M0003_DefaultCategory::class.java,
        M0004_AnimeTablesBatch1::class.java,
        M0005_AnimeTablesBatch2::class.java,
        M0006_AnimeTablesBatch3::class.java,
        M0007_ChapterIsDownloaded::class.java,
        M0008_ChapterPageCount::class.java,
        M0009_ChapterLastReadAt::class.java,
        M0010_MangaAndChapterMeta::class.java,
        M0011_SourceDropPartOfFactorySource::class.java,
        M0012_SourceIsNsfw::class.java,
        M0013_MangaRealUrl::class.java,
        M0014_MangaRemoveLengthLimit::class.java,
        M0015_SourceAndExtensionLangAddLengthLimit::class.java,
        M0016_ChapterIndexRenameToSourceOrder::class.java,
        M0017_ChapterFetchedAt::class.java,
        M0018_MangaInLibraryAt::class.java,
        M0019_RemoveAnime::class.java,
        M0020_AddMangaLastFetchedAtColumns::class.java,
        M0021_GlobalAndCategoryMeta::class.java,
        M0022_MangaThumbnailLastFetched::class.java,
        M0023_CategoryMetaRefFix::class.java,
        M0024_MangaUpdateStrategy::class.java,
        M0025_ChapterRealUrl::class.java,
        M0026_CategoryIncludeInUpdate::class.java,
        M0027_AddDefaultCategory::class.java,
        M0028_AddCascade::class.java,
        M0029_DropMangaDefaultCategory::class.java,
        M0030_FixDateUpload::class.java,
        M0031_AddExtensionRepo::class.java,
        M0032_FixExtensionRepos::class.java,
        M0033_TrackRecord::class.java,
        M0034_CategoryIncludeInDownload::class.java,
        M0035_TrackSearch::class.java,
        M0036_SourceMeta::class.java,
        M0037_RemoveTrackRecordsOfUnsupportedTrackers::class.java,
        M0038_RemoveTrackRecordsOfUnsupportedTrackersII::class.java,
        M0039_DeleteDuplicatedChapters::class.java,
        M0040_AddUniqueConstraintToChapterTable::class.java,
        M0041_FixDownloadedChaptersWithoutPageCount::class.java,
        M0042_MangaRemoveLengthLimit_II::class.java,
        M0043_PreventNegativeLastPageRead::class.java,
        M0044_FixDownloadedChaptersWithoutPageCountII::class.java,
        M0045_PreventDuplicatedChapterPages::class.java,
        M0046_RenamePageImageUrlColumn::class.java,
        M0047_ChapterScanlatorIncreaseLength::class.java,
        M0048_AddTrackingColumns::class.java,
        M0049_FixDuplicatedMetas::class.java,
        M0050_FixHandlingOfTooLongPageImageUrls::class.java,
        M0051_AddKoreaderHashToChapterTable::class.java,
        M0052_RenameCategoryOrderColumn::class.java,
        M0053_TrackersFixIds::class.java,
        M0054_MovePostgresToSuwayomiSchema::class.java,
        M0055_RenameMetaKeys::class.java,
        M0056_SyncYomi::class.java,
        M0057_AddNewExtensionApiFields::class.java,
        M0058_AddExtensionStore::class.java,
        M0059_AddJarUrlToExtensionTable::class.java,
        M0060_MakeExtensionPkgNameUnique::class.java,
        M0061_CreateForeignKeyIndexes::class.java,
        M0062_PreventDuplicatedCategoryManga::class.java,
        M0063_FixSyncYomiTriggers::class.java,
        M0064_AddSyncFlags::class.java,
        M0065_FixSecondsFallbackUploadDate::class.java,
        M0066_GoMangaRuntimeCompatibilityV1::class.java,
    )

    private fun packagedLegacyDriver(): Path =
        Path.of(System.getProperty("java.class.path").substringBefore(File.pathSeparator)).toAbsolutePath()
            .resolveSibling("legacy/h2-1.4.200.jar")
}
