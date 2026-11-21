package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.os.Looper
import ch.qos.logback.classic.Level
import com.typesafe.config.ConfigRenderOptions
import dorkbox.updates.Updates
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.createAppModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.local.LocalSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.json.JavalinJackson3
import io.javalin.json.JsonMapper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.cef.network.CefCookieManager
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import suwayomi.tachidesk.global.impl.KcefWebView.Companion.toCefCookie
import suwayomi.tachidesk.global.impl.sync.SyncManager
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.builtin.komga.BuiltInKomgaLifecycle
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.impl.update.Updater
import suwayomi.tachidesk.manga.impl.util.lang.renameTo
import suwayomi.tachidesk.server.database.DBManager
import suwayomi.tachidesk.server.database.databaseUp
import suwayomi.tachidesk.server.generated.BuildConfig
import suwayomi.tachidesk.server.settings.SettingsRegistry
import suwayomi.tachidesk.server.util.AppMutex.handleAppMutex
import suwayomi.tachidesk.server.util.CEFManager
import suwayomi.tachidesk.server.util.ConfigTypeRegistration
import suwayomi.tachidesk.server.util.ExitCode
import suwayomi.tachidesk.server.util.SystemTray
import suwayomi.tachidesk.server.util.shutdownApp
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.androidCompatModule
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import xyz.nulldev.ts.config.ApplicationRootDir
import xyz.nulldev.ts.config.BASE_LOGGER_NAME
import xyz.nulldev.ts.config.GlobalConfigManager
import xyz.nulldev.ts.config.configManagerModule
import xyz.nulldev.ts.config.initLoggerConfig
import xyz.nulldev.ts.config.setLogLevelFor
import xyz.nulldev.ts.config.updateFileAppender
import java.io.File
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.Security
import java.util.Locale
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

private fun initializeBuiltInKomga() {
    try {
        BuiltInKomgaLifecycle.initialize()
    } catch (error: Throwable) {
        logger.error(error) { "Failed to initialize the built-in Komga provider" }
        shutdownApp(ExitCode.BuiltInKomgaSetupFailure)
    }
}

class ApplicationDirs(
    val dataRoot: String = ApplicationRootDir,
    val tempRoot: String = "${System.getProperty("java.io.tmpdir")}/Tachidesk",
) {
    val extensionsRoot = "$dataRoot/extensions"
    val downloadsRoot
        get() = serverConfig.downloadsPath.value.ifBlank { "$dataRoot/downloads" }
    val localMangaRoot
        get() = serverConfig.localSourcePath.value.ifBlank { "$dataRoot/local" }
    val webUIRoot = "$dataRoot/webUI"
    val webUIServe = "$tempRoot/webUI-serve"
    val automatedBackupRoot
        get() = serverConfig.backupPath.value.ifBlank { "$dataRoot/backups" }

    val tempThumbnailCacheRoot = "$tempRoot/thumbnails"
    val tempMangaCacheRoot = "$tempRoot/manga-cache"

    val thumbnailDownloadsRoot
        get() = "$downloadsRoot/thumbnails"
    val mangaDownloadsRoot
        get() = "$downloadsRoot/mangas"

    val cacheDir = "$dataRoot/cache"
}

@Suppress("DEPRECATION")
class LooperThread : Thread() {
    override fun run() {
        logger.info { "Starting Android Main Loop" }
        Looper.prepareMainLooper()
        Looper.loop()
    }
}

data class ProxySettings(
    val proxyEnabled: Boolean,
    val socksProxyVersion: Int,
    val proxyHost: String,
    val proxyPort: String,
    val proxyUsername: String,
    val proxyPassword: String,
)

data class DatabaseSettings(
    val databaseType: DatabaseType,
    val databaseUrl: String,
    val databaseUsername: String,
    val databasePassword: String,
    val useHikariConnectionPool: Boolean,
)

val androidCompat by lazy { AndroidCompat() }

fun setupLogLevelUpdating(
    configFlow: MutableStateFlow<Boolean>,
    loggerNames: List<String>,
    defaultLevel: Level = Level.INFO,
) {
    serverConfig.subscribeTo(
        configFlow,
        { debugLogsEnabled ->
            loggerNames.forEach { loggerName ->
                setLogLevelFor(loggerName, if (debugLogsEnabled) Level.DEBUG else defaultLevel)
            }
        },
        ignoreInitialValue = false,
    )
}

fun serverModule(applicationDirs: ApplicationDirs): Module =
    module {
        single { applicationDirs }
        single<IUpdater> { Updater() }
        single<JsonMapper> { JavalinJackson3() }
    }

@OptIn(DelicateCoroutinesApi::class)
fun applicationSetup() = applicationSetup {}

@OptIn(DelicateCoroutinesApi::class)
internal fun applicationSetup(onModulesReady: () -> Unit) {
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        KotlinLogging.logger {}.error(throwable) { "unhandled exception" }
    }

    val mainLoop = LooperThread()
    mainLoop.start()

    // register Tachidesk's config which is dubbed "ServerConfig"
    ConfigTypeRegistration.registerCustomTypes()
    GlobalConfigManager.registerModule(
        ServerConfig.register { GlobalConfigManager.config },
    )

    // Application dirs
    val applicationDirs = ApplicationDirs()
    // ── GoManga slim-mode ──
    // The iOS GoManga app uses Suwayomi only as an extension parser host.
    // The flag is read early so startup-only desktop/server guards can be
    // skipped before they spend time on file loggers, loopback probes, or
    // migration scans.
    val slimMode = System.getProperty("suwayomi.tachidesk.gomanga.slimMode", "false") == "true"

    if (!slimMode) {
        initLoggerConfig(
            applicationDirs.dataRoot,
            serverConfig.maxLogFiles.value,
            serverConfig.maxLogFileSize.value,
            serverConfig.maxLogFolderSize.value,
        )

        serverConfig.subscribeTo(
            combine(
                serverConfig.maxLogFiles,
                serverConfig.maxLogFileSize,
                serverConfig.maxLogFolderSize,
            ) { maxLogFiles, maxLogFileSize, maxLogFolderSize ->
                Triple(maxLogFiles, maxLogFileSize, maxLogFolderSize)
            }.distinctUntilChanged(),
            { (maxLogFiles, maxLogFileSize, maxLogFolderSize) ->
                logger.debug {
                    "updateFileAppender: maxLogFiles= $maxLogFiles, maxLogFileSize= $maxLogFileSize, maxLogFolderSize= $maxLogFolderSize"
                }
                updateFileAppender(maxLogFiles, maxLogFileSize, maxLogFolderSize)
            },
        )

        setupLogLevelUpdating(serverConfig.debugLogsEnabled, listOf(BASE_LOGGER_NAME))
    } else {
        setLogLevelFor(BASE_LOGGER_NAME, Level.WARN)
        setLogLevelFor("Exposed", Level.ERROR)
        setLogLevelFor("graphql", Level.WARN)
        setLogLevelFor("notprivacysafe", Level.WARN)
    }

    logger.info { "Running Suwayomi-Server ${BuildConfig.VERSION}" }

    logger.debug {
        "Loaded config:\n" +
            GlobalConfigManager
                .getRedactedConfig(
                    SettingsRegistry
                        .getAll()
                        .filter { !it.value.privacySafe }
                        .keys
                        .toList(),
                ).root()
                .render(ConfigRenderOptions.concise().setFormatted(true))
    }

    logger.debug { "Data Root directory is set to: ${applicationDirs.dataRoot}" }

    if (slimMode) {
        thread(start = true, isDaemon = true, name = "gomanga-bouncycastle-init") {
            Thread.sleep(2_000)
            Security.addProvider(BouncyCastleProvider())
            logger.info { "GoManga slim-mode: BouncyCastle provider registered in background" }
        }
    }

    if (!slimMode) {
        // Migrate Directories from old versions
        File("$ApplicationRootDir/manga-thumbnails").renameTo(applicationDirs.tempThumbnailCacheRoot)
        File("$ApplicationRootDir/manga-local").renameTo(applicationDirs.localMangaRoot)
        File("$ApplicationRootDir/anime-thumbnails").delete()

        // make dirs we need
        listOf(
            applicationDirs.dataRoot,
            applicationDirs.extensionsRoot,
            applicationDirs.extensionsRoot + "/icon",
            applicationDirs.tempThumbnailCacheRoot,
            applicationDirs.downloadsRoot,
            applicationDirs.localMangaRoot,
        ).forEach { File(it).mkdirs() }
    } else {
        logger.info { "GoManga slim-mode: skipping startup directory migration/materialization" }
    }

    // initialize Koin modules
    val app = App()
    startKoin {
        modules(
            createAppModule(app),
            androidCompatModule(),
            configManagerModule(),
            serverModule(applicationDirs),
            module {
                single<KcefWebViewProvider.InitBrowserHandler> {
                    object : KcefWebViewProvider.InitBrowserHandler {
                        override fun init(provider: KcefWebViewProvider) {
                            val networkHelper = Injekt.get<NetworkHelper>()
                            val logger = KotlinLogging.logger {}
                            logger.info { "Start loading cookies" }
                            CefCookieManager.getGlobalManager().apply {
                                val cookies = networkHelper.cookieStore.getStoredCookies()
                                for (cookie in cookies) {
                                    try {
                                        if (!setCookie(
                                                "https://" + cookie.domain,
                                                cookie.toCefCookie(),
                                            )
                                        ) {
                                            throw Exception()
                                        }
                                    } catch (e: Exception) {
                                        logger.warn(e) { "Loading cookie ${cookie.name} failed" }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    onModulesReady()

    // Make sure only one instance of the app is running
    if (!slimMode) {
        handleAppMutex()
    } else {
        logger.info { "GoManga slim-mode: skipping AppMutex self-probe" }
    }

    // Load Android compatibility dependencies
    AndroidCompatInitializer().init()
    // start app
    androidCompat.startApp(app)

    // Delete files before any extension-related logic can be executed that causes the jars to get loaded
    Extension.cleanupExtensionFiles()

    // Initialize NetworkHelper early
    Injekt
        .get<NetworkHelper>()
        .userAgentFlow
        .onEach { System.setProperty("http.agent", it) }
        .launchIn(GlobalScope)

    if (!slimMode) {
        // create or update conf file if doesn't exist
        try {
            val dataConfFile = File("${applicationDirs.dataRoot}/server.conf")
            if (!dataConfFile.exists()) {
                JavalinSetup::class.java.getResourceAsStream("/server-reference.conf").use { input ->
                    dataConfFile.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                // make sure the user config file is up-to-date
                GlobalConfigManager.updateUserConfig {
                    try {
                        migrateConfig(this, it)
                    } catch (e: Throwable) {
                        logger.error(e) { "Failed to migrate config" }
                        shutdownApp(ExitCode.ConfigMigrationFailure)
                    }
                    this
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception while creating initial server.conf" }
            shutdownApp(ExitCode.SetupConfFileFailed)
        }
    } else {
        logger.info { "GoManga slim-mode: skipping server.conf materialization" }
    }

    if (!slimMode) {
        // copy local source icon
        try {
            val localSourceIconFile = File("${applicationDirs.extensionsRoot}/icon/localSource.png")
            if (!localSourceIconFile.exists()) {
                JavalinSetup::class.java.getResourceAsStream("/icon/localSource.png").use { input ->
                    localSourceIconFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception while copying Local source's icon" }
            shutdownApp(ExitCode.LocalSourceIconCopyFailure)
        }
    } else {
        logger.info { "GoManga slim-mode: skipping local source icon materialization" }
    }

    // fixes #119 , ref:
    // https://github.com/Suwayomi/Suwayomi-Server/issues/119#issuecomment-894681292 , source Id
    // calculation depends on String.lowercase()
    Locale.setDefault(Locale.ENGLISH)

    if (!slimMode) {
        // Initialize the localization service
        LocalizationHelper.initialize()
        logger.debug {
            "Localization service initialized. Supported languages: ${LocalizationHelper.getSupportedLocales()}"
        }
    } else {
        logger.info { "GoManga slim-mode: skipping localization service initialization" }
    }

    if (slimMode) {
        logger.info { "GoManga slim-mode: preparing the embedded database" }
        suwayomi.tachidesk.server.database.EmbeddedDatabaseAdmission.prepare(applicationDirs)
        DBManager.setupDatabase()
    } else {
        runBlocking {
            runMigrations(applicationDirs) {
                databaseUp()
            }
        }
    }

    try {
        LocalSource.register()
    } catch (e: Exception) {
        logger.error(e) { "Failed to setup LocalSource" }
        shutdownApp(ExitCode.LocalSourceSetupFailure)
    }
    initializeBuiltInKomga()

    if (!slimMode) {
        serverConfig.subscribeTo(
            combine<Any, DatabaseSettings>(
                serverConfig.databaseType,
                serverConfig.databaseUrl,
                serverConfig.databaseUsername,
                serverConfig.databasePassword,
                serverConfig.useHikariConnectionPool,
            ) { vargs ->
                DatabaseSettings(
                    vargs[0] as DatabaseType,
                    vargs[1] as String,
                    vargs[2] as String,
                    vargs[3] as String,
                    vargs[4] as Boolean,
                )
            }.distinctUntilChanged(),
            { (databaseType, databaseUrl, _databaseUsername, _databasePassword, hikariCp) ->
                logger.info {
                    "Database changed - type=$databaseType url=$databaseUrl, username=[REDACTED], password=[REDACTED], hikaricp=$hikariCp"
                }
                databaseUp()

                LocalSource.register()
                initializeBuiltInKomga()
            },
            ignoreInitialValue = true,
        )

        Updates.ENABLE = false
        // create system tray
        serverConfig.subscribeTo(
            serverConfig.systemTrayEnabled,
            { systemTrayEnabled ->
                try {
                    if (systemTrayEnabled) {
                        SystemTray.create()
                    } else {
                        SystemTray.remove()
                    }
                } catch (e: Throwable) {
                    // cover both java.lang.Exception and java.lang.Error
                    logger.error(e) { "Failed to create/remove SystemTray due to" }
                }
            },
            ignoreInitialValue = false,
        )
    }

    setLogLevelFor("org.eclipse.jetty", Level.OFF)
    setLogLevelFor("com.zaxxer.hikari", Level.WARN)

    if (!slimMode) {
        // socks proxy settings
        serverConfig.subscribeTo(
            combine<Any, ProxySettings>(
                serverConfig.socksProxyEnabled,
                serverConfig.socksProxyVersion,
                serverConfig.socksProxyHost,
                serverConfig.socksProxyPort,
                serverConfig.socksProxyUsername,
                serverConfig.socksProxyPassword,
            ) { vargs ->
                ProxySettings(
                    vargs[0] as Boolean,
                    vargs[1] as Int,
                    vargs[2] as String,
                    vargs[3] as String,
                    vargs[4] as String,
                    vargs[5] as String,
                )
            }.distinctUntilChanged(),
            { (proxyEnabled, proxyVersion, proxyHost, proxyPort, proxyUsername, proxyPassword) ->
                logger.info {
                    "Socks Proxy changed - enabled=$proxyEnabled address=$proxyHost:$proxyPort , username=[REDACTED], password=[REDACTED]"
                }
                if (proxyEnabled) {
                    System.setProperty("socksProxyHost", proxyHost)
                    System.setProperty("socksProxyPort", proxyPort)
                    System.setProperty("socksProxyVersion", proxyVersion.toString())

                    Authenticator.setDefault(
                        object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication? {
                                if (requestingProtocol.startsWith("SOCKS", ignoreCase = true)) {
                                    return PasswordAuthentication(
                                        proxyUsername,
                                        proxyPassword.toCharArray(),
                                    )
                                }

                                return null
                            }
                        },
                    )
                } else {
                    System.clearProperty("socksProxyHost")
                    System.clearProperty("socksProxyPort")
                    System.clearProperty("socksProxyVersion")

                    Authenticator.setDefault(null)
                }
            },
            ignoreInitialValue = false,
        )
    }

    // AES/CBC/PKCS7Padding Cypher provider for zh.copymanga.
    // Extensions need it. In GoManga slim-mode it is registered by the early
    // background thread above so it does not block loopback listener startup.
    if (!slimMode) {
        Security.addProvider(BouncyCastleProvider())
    }

    if (!slimMode) {
        // start automated global updates
        val updater = Injekt.get<IUpdater>()
        (updater as Updater).scheduleUpdateTask()

        // start automated backups
        ProtoBackupExport.scheduleAutomatedBackupTask()

        // start DownloadManager and restore + resume downloads
        DownloadManager.restoreAndResumeDownloads()

        // v2.3 side-effect: schedule the sync task
        SyncManager.scheduleSyncTask()

        // asynchronously initialize CEF (native Chromium)
        GlobalScope.launch {
            CEFManager.init()
        }

        // v2.3 side-effect: keep extension-store prefs mirrored into the DB
        serverConfig.subscribeTo(
            serverConfig.extensionStores,
            { _ ->
                ExtensionStoreService.syncPrefsToDb()
            },
            ignoreInitialValue = false,
        )
    } else {
        // [GoManga slim-mode] Skip every heavy desktop/server startup side-effect:
        // SystemTray, SocksProxy, Updater, AutomatedBackups,
        // DownloadManager.restoreAndResume, and v2.3's new SyncManager schedule +
        // ExtensionStore prefs->db sync subscription.
        //
        // [KCEF/JCEF no-op] CEFManager.init() (native Chromium) is intentionally
        // NOT called on the GoManga iOS runtime. In the on-Mac OpenJ9 -Xint boot
        // test the native CefApp init crashed with a SIGSEGV that the onError
        // handler does NOT catch, taking down the JVM ~14s AFTER Javalin was
        // already listening. iOS has no native Chromium at all, so this eager init
        // must never run on the embedded runtime. The no-op is expressed by
        // skipping CEFManager.init() here rather than editing CEFManager.kt.
        logger.info {
            "GoManga slim-mode: skipping SystemTray, SocksProxy, Updater, AutomatedBackups, " +
                "DownloadManager.restoreAndResume, SyncManager, ExtensionStore sync, and CEF init"
        }
    }
}
