package suwayomi.tachidesk.server.database

import de.neonew.exposed.migrations.Migration
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.server.ApplicationDirs
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties
import org.h2.Driver

internal object EmbeddedDatabaseAdmission {
    // A current root with at least this many chapters is copied to a private workspace before the
    // pending M0065 bulk UPDATE. Row cardinality, rather than file size, catches compact H23
    // stores and avoids staging tiny roots. The threshold is explicit for capacity planning.
    private const val LARGE_M0065_STAGE_ROWS = 5_000

    fun prepare(dirs: ApplicationDirs, legacyDriver: Path = packagedLegacyDriver()) =
        prepareDuringSetup(dirs, legacyDriver) {}

    // The read-only connection remains open through setup. Defer all writes,
    // recovery and conversion locks until Android and extension services are ready.
    @OptIn(ExperimentalKeywordApi::class)
    fun prepareDuringSetup(
        dirs: ApplicationDirs,
        legacyDriver: Path = packagedLegacyDriver(),
        awaitServerSetup: () -> Unit,
    ) {
        val file = Path.of(dirs.dataRoot, "database.mv.db")
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Expected existing database or packaged seed" }
        val registrations = migrationClasses
        val names = registrations.mapIndexed { index, name ->
            check(name.substringBefore('_').removePrefix("M").toInt() == index + 1)
            name.substringAfter('_')
        }
        val extensions = Path.of(dirs.extensionsRoot)

        fun migrate(path: Path, privateStage: Boolean = false) {
            awaitServerSetup()
            require(';' !in path.toString())
            val database = Database.connect(
                "jdbc:h2:${path.toAbsolutePath().toString().removeSuffix(".mv.db")};IFEXISTS=TRUE",
                driver = "org.h2.Driver", user = "", password = "",
                databaseConfig = DatabaseConfig { preserveKeywordCasing = false },
            )
            try {
                val migrations = registrations.map { name ->
                    Class.forName("suwayomi.tachidesk.server.database.migration.$name")
                        .asSubclass(Migration::class.java).getDeclaredConstructor().newInstance()
                }
                EmbeddedSchemaRunner.upgrade(database, migrations, privateStage) { step, connection ->
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
            awaitServerSetup()
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
            val originalRead = readOnly && path == file
            fun identity() = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val original = if (originalRead) identity() else null
            val mode = if (readOnly) ";ACCESS_MODE_DATA=r" else ""
            return Driver().connect("jdbc:h2:$database;IFEXISTS=TRUE;TRACE_LEVEL_FILE=0$mode", properties).use {
                val prefix = EmbeddedSchemaRunner.preflight(it, names)
                if (originalRead) {
                    awaitServerSetup()
                    val current = identity()
                    check(original != null && current.fileKey() == original.fileKey() &&
                        current.size() == original.size() && current.lastModifiedTime() == original.lastModifiedTime()) {
                        "Embedded database changed during read-only admission"
                    }
                }
                prefix
            }
        }
        fun recover() = EmbeddedLegacyH2.recoverCurrent(file) { staged ->
            if (inspect(staged, readOnly = false) < registrations.size) migrate(staged, privateStage = true)
            check(inspect(staged, readOnly = true) == registrations.size)
        }

        fun hasLargeCurrentRoot(path: Path): Boolean {
            val database = path.toAbsolutePath().toString().removeSuffix(".mv.db")
            return Driver().connect(
                "jdbc:h2:$database;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0",
                Properties().apply { setProperty("user", ""); setProperty("password", "") },
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT ID FROM CHAPTER OFFSET ${LARGE_M0065_STAGE_ROWS - 1} ROWS FETCH NEXT 1 ROW ONLY",
                    ).use { it.next() }
                }
            }
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
                    if (completed < registrations.size) {
                        if (completed <= 64 && hasLargeCurrentRoot(file)) {
                            if (locked) recover() else withConversionLock(::recover)
                        } else {
                            migrate(file)
                        }
                    }
                }
                "format:1" -> {
                    EmbeddedDatabaseUpgradeProgress.convertingFormat()
                    val budget = Math.addExact(Math.multiplyExact(Files.size(file), 4L), 32L * 1024 * 1024)
                    EmbeddedLegacyH2.convert(file, legacyDriver, budget, null, names) { staged -> migrate(staged, privateStage = true) }
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
    private val migrationClasses = listOf(
        "M0001_Initial",
        "M0002_ChapterTableIndexRename",
        "M0003_DefaultCategory",
        "M0004_AnimeTablesBatch1",
        "M0005_AnimeTablesBatch2",
        "M0006_AnimeTablesBatch3",
        "M0007_ChapterIsDownloaded",
        "M0008_ChapterPageCount",
        "M0009_ChapterLastReadAt",
        "M0010_MangaAndChapterMeta",
        "M0011_SourceDropPartOfFactorySource",
        "M0012_SourceIsNsfw",
        "M0013_MangaRealUrl",
        "M0014_MangaRemoveLengthLimit",
        "M0015_SourceAndExtensionLangAddLengthLimit",
        "M0016_ChapterIndexRenameToSourceOrder",
        "M0017_ChapterFetchedAt",
        "M0018_MangaInLibraryAt",
        "M0019_RemoveAnime",
        "M0020_AddMangaLastFetchedAtColumns",
        "M0021_GlobalAndCategoryMeta",
        "M0022_MangaThumbnailLastFetched",
        "M0023_CategoryMetaRefFix",
        "M0024_MangaUpdateStrategy",
        "M0025_ChapterRealUrl",
        "M0026_CategoryIncludeInUpdate",
        "M0027_AddDefaultCategory",
        "M0028_AddCascade",
        "M0029_DropMangaDefaultCategory",
        "M0030_FixDateUpload",
        "M0031_AddExtensionRepo",
        "M0032_FixExtensionRepos",
        "M0033_TrackRecord",
        "M0034_CategoryIncludeInDownload",
        "M0035_TrackSearch",
        "M0036_SourceMeta",
        "M0037_RemoveTrackRecordsOfUnsupportedTrackers",
        "M0038_RemoveTrackRecordsOfUnsupportedTrackersII",
        "M0039_DeleteDuplicatedChapters",
        "M0040_AddUniqueConstraintToChapterTable",
        "M0041_FixDownloadedChaptersWithoutPageCount",
        "M0042_MangaRemoveLengthLimit_II",
        "M0043_PreventNegativeLastPageRead",
        "M0044_FixDownloadedChaptersWithoutPageCountII",
        "M0045_PreventDuplicatedChapterPages",
        "M0046_RenamePageImageUrlColumn",
        "M0047_ChapterScanlatorIncreaseLength",
        "M0048_AddTrackingColumns",
        "M0049_FixDuplicatedMetas",
        "M0050_FixHandlingOfTooLongPageImageUrls",
        "M0051_AddKoreaderHashToChapterTable",
        "M0052_RenameCategoryOrderColumn",
        "M0053_TrackersFixIds",
        "M0054_MovePostgresToSuwayomiSchema",
        "M0055_RenameMetaKeys",
        "M0056_SyncYomi",
        "M0057_AddNewExtensionApiFields",
        "M0058_AddExtensionStore",
        "M0059_AddJarUrlToExtensionTable",
        "M0060_MakeExtensionPkgNameUnique",
        "M0061_CreateForeignKeyIndexes",
        "M0062_PreventDuplicatedCategoryManga",
        "M0063_FixSyncYomiTriggers",
        "M0064_AddSyncFlags",
        "M0065_FixSecondsFallbackUploadDate",
        "M0066_GoMangaRuntimeCompatibilityV1",
    )

    private fun packagedLegacyDriver(): Path =
        Path.of(System.getProperty("java.class.path").substringBefore(File.pathSeparator)).toAbsolutePath()
            .resolveSibling("legacy/h2-1.4.200.jar")
}
