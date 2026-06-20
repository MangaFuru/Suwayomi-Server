package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.core.security.RouteRole
import io.javalin.http.staticfiles.Location
import io.javalin.plugin.openapi.OpenApiOptions
import io.javalin.plugin.openapi.OpenApiPlugin
import io.javalin.plugin.openapi.ui.SwaggerOptions
import io.swagger.v3.oas.models.info.Info
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.kodein.di.DI
import org.kodein.di.conf.global
import org.kodein.di.instance
import suwayomi.tachidesk.global.GlobalAPI
import suwayomi.tachidesk.global.controller.SettingsController
import suwayomi.tachidesk.graphql.GraphQL
import suwayomi.tachidesk.manga.MangaAPI
import suwayomi.tachidesk.server.util.Browser
import suwayomi.tachidesk.server.util.WebInterfaceManager
import java.io.IOException
import java.lang.IllegalArgumentException
import java.util.concurrent.CompletableFuture
import kotlin.NoSuchElementException
import kotlin.concurrent.thread

object JavalinSetup {
    private val logger = KotlinLogging.logger {}

    private val applicationDirs by DI.global.instance<ApplicationDirs>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * GoManga: the live Javalin app, retained so the embedded HTTP server can be
     * restarted inside the already-running JVM (the JVM itself is a per-process
     * singleton and cannot be re-created on iOS). Set after each [javalinSetup].
     */
    @Volatile
    private var currentApp: Javalin? = null

    /** GoManga: the JVM-shutdown hook is process-scoped — install it only once. */
    @Volatile
    private var shutdownHookInstalled = false

    fun <T> future(block: suspend CoroutineScope.() -> T): CompletableFuture<T> {
        return scope.future(block = block)
    }

    fun javalinSetup() {
        val slimMode = System.getProperty("suwayomi.tachidesk.gomanga.slimMode", "false") == "true"
        val graphQLEnabled =
            !slimMode || System.getProperty("suwayomi.tachidesk.gomanga.graphqlEnabled", "false") == "true"
        val server = Server()
        val connector =
            ServerConnector(server).apply {
                host = serverConfig.ip.value
                port = serverConfig.port.value
            }
        server.addConnector(connector)

        if (!slimMode) {
            serverConfig.subscribeTo(combine(serverConfig.ip, serverConfig.port) { ip, port -> Pair(ip, port) }, { (newIp, newPort) ->
                val oldIp = connector.host
                val oldPort = connector.port

                connector.host = newIp
                connector.port = newPort
                connector.stop()
                connector.start()

                logger.info { "Server ip and/or port changed from $oldIp:$oldPort to $newIp:$newPort " }
            })
        }

        val app =
            Javalin.create { config ->
                if (!slimMode && serverConfig.webUIEnabled.value) {
                    val serveWebUI = {
                        config.addSinglePageRoot("/", applicationDirs.webUIRoot + "/index.html", Location.EXTERNAL)
                    }
                    WebInterfaceManager.setServeWebUI(serveWebUI)

                    runBlocking {
                        WebInterfaceManager.setupWebUI()
                    }

                    logger.info { "Serving web static files for ${serverConfig.webUIFlavor.value}" }
                    config.addStaticFiles(applicationDirs.webUIRoot, Location.EXTERNAL)
                    serveWebUI()

                    config.registerPlugin(OpenApiPlugin(getOpenApiOptions()))
                }

                config.server { server }

                if (!slimMode) {
                    config.enableCorsForAllOrigins()

                    config.accessManager { handler, ctx, _ ->
                        fun credentialsValid(): Boolean {
                            val (username, password) = ctx.basicAuthCredentials()
                            return username == serverConfig.basicAuthUsername.value && password == serverConfig.basicAuthPassword.value
                        }

                        if (serverConfig.basicAuthEnabled.value && !(ctx.basicAuthCredentialsExist() && credentialsValid())) {
                            ctx.header("WWW-Authenticate", "Basic")
                            ctx.status(401).json("Unauthorized")
                        } else {
                            handler.handle(ctx)
                        }
                    }
                }
            }

        if (!slimMode) {
            app.events { event ->
                event.serverStarted {
                    if (serverConfig.initialOpenInBrowserEnabled.value) {
                        Browser.openInBrowser()
                    }
                }
            }
        }

        // when JVM is prompted to shutdown, stop javalin gracefully. GoManga:
        // install the process-scoped hook once and target the CURRENT app, so a
        // restart (which replaces `currentApp`) doesn't pile up stale hooks.
        if (!shutdownHookInstalled) {
            shutdownHookInstalled = true
            Runtime.getRuntime().addShutdownHook(
                thread(start = false) {
                    currentApp?.stop()
                },
            )
        }

        app.exception(NullPointerException::class.java) { e, ctx ->
            logger.error("NullPointerException while handling the request", e)
            ctx.status(404)
        }
        app.exception(NoSuchElementException::class.java) { e, ctx ->
            logger.error("NoSuchElementException while handling the request", e)
            ctx.status(404)
        }
        app.exception(IOException::class.java) { e, ctx ->
            logger.error("IOException while handling the request", e)
            ctx.status(500)
            ctx.result(e.message ?: "Internal Server Error")
        }

        app.exception(IllegalArgumentException::class.java) { e, ctx ->
            logger.error("IllegalArgumentException while handling the request", e)
            ctx.status(400)
            ctx.result(e.message ?: "Bad Request")
        }

        app.routes {
            path("api/") {
                path("v1/") {
                    if (!slimMode) {
                        GlobalAPI.defineEndpoints()
                    } else {
                        path("settings") {
                            get("about", SettingsController.about)
                        }
                    }
                    MangaAPI.defineEndpoints()
                }
                if (graphQLEnabled) {
                    GraphQL.defineEndpoints()
                }
            }
        }

        app.start()
        currentApp = app
    }

    /**
     * GoManga: restart ONLY the embedded HTTP server (Javalin + its Jetty
     * connector) inside the already-running JVM, re-binding the same port.
     *
     * The SDK calls this over JNI when the host app detects the loopback REST
     * listener has died — e.g. after an iOS background freeze→thaw left Jetty's
     * NIO acceptor in a dead state — because the OpenJ9 JVM is a per-process
     * singleton that cannot be re-created in-process (otherwise the user must
     * kill the app). It stops the previous Javalin app (releasing the port) and
     * re-runs [javalinSetup] on a fresh `Server`; it deliberately does NOT touch
     * the DB, Koin/DI modules, or the source/extension runtime, which stay live
     * from the original boot. Returns once the new listener is up; throws if the
     * rebind fails, which the SDK surfaces to the host.
     *
     * Invoked from the native layer (`gomanga_jvm_restart_suwayomi`) on a freshly
     * attached thread; never call it concurrently with itself.
     */
    @JvmStatic
    fun gomangaRestartHttpServer() {
        logger.info { "GoManga: restarting embedded HTTP server (in-JVM)…" }
        withAppClassLoader {
            stopCurrentApp()
            javalinSetup()
        }
        logger.info { "GoManga: embedded HTTP server restarted" }
    }

    /**
     * GoManga: STOP the embedded HTTP server (Javalin/Jetty) inside the running
     * JVM WITHOUT restarting it, releasing the listener + Jetty threads + the
     * loopback port. The host calls this when it proactively pauses source work
     * for a long iOS background: a stopped HTTP layer means a much smaller
     * background footprint (lower jetsam risk) AND no live NIO acceptor for an
     * iOS freeze→thaw to corrupt — so [gomangaRestartHttpServer] on return is a
     * clean rebind. The DB/Koin/source runtime stay live from the original boot.
     */
    @JvmStatic
    fun gomangaStopHttpServer() {
        logger.info { "GoManga: stopping embedded HTTP server (in-JVM)…" }
        withAppClassLoader {
            stopCurrentApp()
        }
        logger.info { "GoManga: embedded HTTP server stopped" }
    }

    /**
     * Run [block] with the thread context classloader pinned to the application
     * loader. The native stop/restart entries arrive on a freshly JNI-attached
     * thread whose context classloader is the system loader, NOT the loader that
     * loaded the Suwayomi fat jar. Jetty/Javalin resolve servlet + WebSocket
     * factory classes (e.g. WebSocketServerFactory) through the THREAD CONTEXT
     * classloader during app.start(); without this they throw
     * ClassNotFoundException — even though the same classes loaded fine on the
     * original boot, which ran on the primordial thread.
     */
    private fun withAppClassLoader(block: () -> Unit) {
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = JavalinSetup::class.java.classLoader
        try {
            block()
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }

    /** Stop + clear the current Javalin app. A dead Jetty can throw on stop; the
     *  caller proceeds regardless (a fresh server is created on the next start). */
    private fun stopCurrentApp() {
        try {
            currentApp?.stop()
        } catch (e: Throwable) {
            logger.warn(e) { "GoManga: ignoring error while stopping Javalin" }
        }
        currentApp = null
    }

    private fun getOpenApiOptions(): OpenApiOptions {
        val applicationInfo =
            Info().apply {
                version("1.0")
                description("Suwayomi-Server Api")
            }
        return OpenApiOptions(applicationInfo).apply {
            path("/api/openapi.json")
            swagger(
                SwaggerOptions("/api/swagger-ui").apply {
                    title("Suwayomi-Server Swagger Documentation")
                },
            )
        }
    }

    object Auth {
        enum class Role : RouteRole { ANYONE, USER_READ, USER_WRITE }
    }
}
