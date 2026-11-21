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
import java.sql.DriverManager

internal object EmbeddedDatabaseAdmission {
    @OptIn(ExperimentalKeywordApi::class)
    fun prepare(dirs: ApplicationDirs, legacyDriver: Path = packagedLegacyDriver()) {
        val file = Path.of(dirs.dataRoot, "database.mv.db")
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Expected existing database or packaged seed" }
        val migrations = registrations()
        val extensions = Path.of(dirs.extensionsRoot)

        fun migrate(path: Path) {
            require(';' !in path.toString())
            val database = Database.connect(
                "jdbc:h2:${path.toAbsolutePath().toString().removeSuffix(".mv.db")};IFEXISTS=TRUE",
                driver = "org.h2.Driver", user = "", password = "",
                databaseConfig = DatabaseConfig { preserveKeywordCasing = false },
            )
            try {
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
        fun admit() {
            when (fileFormat()) {
                "format:3" -> {
                    val path = file.toAbsolutePath().toString().removeSuffix(".mv.db")
                    require(';' !in path)
                    DriverManager.getConnection("jdbc:h2:$path;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0", "", "").use {
                        EmbeddedSchemaRunner.preflight(it, migrations)
                    }
                    migrate(file)
                }
                "format:1" -> {
                    EmbeddedDatabaseUpgradeProgress.convertingFormat()
                    val budget = Math.addExact(Math.multiplyExact(Files.size(file), 4L), 32L * 1024 * 1024)
                    EmbeddedLegacyH2.convert(file, legacyDriver, budget, null, migrations.sortedBy { it.version }.map { it.name }, ::migrate)
                }
                else -> error("Unsupported embedded database file format")
            }
        }

        val lockFile = file.resolveSibling(".gomanga-h2-conversion.lock")
        if (fileFormat() == "format:1" || Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                checkNotNull(channel.tryLock()) { "Embedded format conversion is already active" }.use {
                    admit()
                    EmbeddedLegacyH2.removeAbandonedWorkspaces(file)
                }
            }
        } else {
            admit()
        }
    }

    // The iOS classpath may split migration classes across two JARs.
    private fun registrations(): List<Migration> = listOf(
        M0001_Initial(),
        M0002_ChapterTableIndexRename(),
        M0003_DefaultCategory(),
        M0004_AnimeTablesBatch1(),
        M0005_AnimeTablesBatch2(),
        M0006_AnimeTablesBatch3(),
        M0007_ChapterIsDownloaded(),
        M0008_ChapterPageCount(),
        M0009_ChapterLastReadAt(),
        M0010_MangaAndChapterMeta(),
        M0011_SourceDropPartOfFactorySource(),
        M0012_SourceIsNsfw(),
        M0013_MangaRealUrl(),
        M0014_MangaRemoveLengthLimit(),
        M0015_SourceAndExtensionLangAddLengthLimit(),
        M0016_ChapterIndexRenameToSourceOrder(),
        M0017_ChapterFetchedAt(),
        M0018_MangaInLibraryAt(),
        M0019_RemoveAnime(),
        M0020_AddMangaLastFetchedAtColumns(),
        M0021_GlobalAndCategoryMeta(),
        M0022_MangaThumbnailLastFetched(),
        M0023_CategoryMetaRefFix(),
        M0024_MangaUpdateStrategy(),
        M0025_ChapterRealUrl(),
        M0026_CategoryIncludeInUpdate(),
        M0027_AddDefaultCategory(),
        M0028_AddCascade(),
        M0029_DropMangaDefaultCategory(),
        M0030_FixDateUpload(),
        M0031_AddExtensionRepo(),
        M0032_FixExtensionRepos(),
        M0033_TrackRecord(),
        M0034_CategoryIncludeInDownload(),
        M0035_TrackSearch(),
        M0036_SourceMeta(),
        M0037_RemoveTrackRecordsOfUnsupportedTrackers(),
        M0038_RemoveTrackRecordsOfUnsupportedTrackersII(),
        M0039_DeleteDuplicatedChapters(),
        M0040_AddUniqueConstraintToChapterTable(),
        M0041_FixDownloadedChaptersWithoutPageCount(),
        M0042_MangaRemoveLengthLimit_II(),
        M0043_PreventNegativeLastPageRead(),
        M0044_FixDownloadedChaptersWithoutPageCountII(),
        M0045_PreventDuplicatedChapterPages(),
        M0046_RenamePageImageUrlColumn(),
        M0047_ChapterScanlatorIncreaseLength(),
        M0048_AddTrackingColumns(),
        M0049_FixDuplicatedMetas(),
        M0050_FixHandlingOfTooLongPageImageUrls(),
        M0051_AddKoreaderHashToChapterTable(),
        M0052_RenameCategoryOrderColumn(),
        M0053_TrackersFixIds(),
        M0054_MovePostgresToSuwayomiSchema(),
        M0055_RenameMetaKeys(),
        M0056_SyncYomi(),
        M0057_AddNewExtensionApiFields(),
        M0058_AddExtensionStore(),
        M0059_AddJarUrlToExtensionTable(),
        M0060_MakeExtensionPkgNameUnique(),
        M0061_CreateForeignKeyIndexes(),
        M0062_PreventDuplicatedCategoryManga(),
        M0063_FixSyncYomiTriggers(),
        M0064_AddSyncFlags(),
        M0065_FixSecondsFallbackUploadDate(),
        M0066_GoMangaRuntimeCompatibilityV1(),
    )

    private fun packagedLegacyDriver(): Path =
        Path.of(System.getProperty("java.class.path").substringBefore(File.pathSeparator)).toAbsolutePath()
            .resolveSibling("legacy/h2-1.4.200.jar")
}
