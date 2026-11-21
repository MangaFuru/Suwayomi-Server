package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.config.RoutesConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import io.javalin.http.RedirectResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.json.JavalinJackson3
import io.javalin.rendering.template.JavalinJte
import io.javalin.websocket.WsContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.core.CloseStatus
import suwayomi.tachidesk.global.GlobalAPI
import suwayomi.tachidesk.global.controller.SettingsController
import suwayomi.tachidesk.graphql.GraphQL
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.manga.MangaAPI
import suwayomi.tachidesk.opds.OpdsAPI
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.getUserFromContext
import suwayomi.tachidesk.server.user.getUserFromWsContext
import suwayomi.tachidesk.server.util.Browser
import suwayomi.tachidesk.server.util.ServerSubpath
import suwayomi.tachidesk.server.util.WebInterfaceManager
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private data class GoMangaSourceHttpError(
    val type: String = "gomanga_source_http",
    val status: Int,
)

object JavalinSetup {
    private val logger = KotlinLogging.logger {}

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

    fun <T> future(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = scope.future(block = block)

    fun javalinSetup() = startPrepared(prepareHttp())

    internal fun prepareHttp(): Javalin {
        // ── GoManga slim-mode ──
        // The iOS GoManga embed uses Suwayomi only as an extension parser host, so
        // it registers ONLY the minimal route surface (settings/about readiness +
        // the MangaAPI source/manga route groups) and skips the full stock REST/
        // OPDS/WebSocket/GraphQL/webUI surface + the desktop auth wiring to cut
        // cold start. GraphQL can be re-enabled independently for debugging.
        val slimMode = System.getProperty("suwayomi.tachidesk.gomanga.slimMode", "false") == "true"
        val graphQLEnabled =
            !slimMode || System.getProperty("suwayomi.tachidesk.gomanga.graphqlEnabled", "false") == "true"
        val app =
            Javalin.create { config ->
                if (!slimMode) {
                    val templateEngine = TemplateEngine.createPrecompiled(ContentType.Html)
                    config.fileRenderer(JavalinJte(templateEngine))
                }

                config.jsonMapper(JavalinJackson3())

                // GoManga: skip webUI static-file serving (and its async copy/setup
                // I/O) in slim mode — the iOS embed never serves the SPA.
                if (!slimMode) {
                    WebInterfaceManager.setup(config)
                }

                // config.registerPlugin(OpenApiPlugin(getOpenApiOptions()))

                var connectorAdded = false
                config.jetty.modifyServer { server ->
                    if (!connectorAdded) {
                        val connector =
                            ServerConnector(server).apply {
                                host = serverConfig.ip.value
                                port = serverConfig.port.value
                            }
                        server.addConnector(connector)

                        serverConfig.subscribeTo(
                            combine(
                                serverConfig.ip,
                                serverConfig.port,
                            ) { ip, port -> Pair(ip, port) },
                            { (newIp, newPort) ->
                                val oldIp = connector.host
                                val oldPort = connector.port

                                connector.host = newIp
                                connector.port = newPort
                                if (server.isStarted) {
                                    connector.stop()
                                    connector.start()
                                }

                                logger.info { "Server ip and/or port changed from $oldIp:$oldPort to $newIp:$newPort " }
                            },
                        )
                        connectorAdded = true
                    }
                }

                // GoManga: the iOS embed talks to a fixed loopback origin and never
                // needs browser CORS; skip it in slim mode.
                if (!slimMode) {
                    config.bundledPlugins.enableCors { cors ->
                        cors.addRule {
                            it.allowCredentials = true
                            it.reflectClientOrigin = true
                        }
                    }
                }

                config.routes.defineCore()
                config.routes.apiBuilder {
                    path(ServerSubpath.maybeAddAsPrefix("api/")) {
                        if (slimMode) {
                            // GoManga slim surface: ONLY the readiness probe the host
                            // polls + the MangaAPI route groups (extension/source/
                            // manga, plus the gomanga groups the other patches add).
                            // Stock GlobalAPI/OPDS and the catch-all 404 are dropped.
                            path("v1/") {
                                path("settings") {
                                    get("about", SettingsController.about)
                                }
                                MangaAPI.defineEndpoints()
                            }
                            if (graphQLEnabled) {
                                GraphQL.defineEndpoints()
                            }
                        } else {
                            path("v1/") {
                                GlobalAPI.defineEndpoints()
                                MangaAPI.defineEndpoints()
                            }

                            OpdsAPI.defineEndpoints()
                            GraphQL.defineEndpoints()

                            after { ctx ->
                                // If not matched, the request was for an invalid endpoint
                                // Return a 404 instead of redirecting to the UI for usability
                                if (ctx.endpoints().lastHttpEndpoint()?.path == "*") {
                                    throw NotFoundResponse()
                                }
                            }
                        }
                    }
                }

                if (!slimMode) {
                    config.events.serverStarted {
                        val addresses =
                            try {
                                when (serverConfig.ip.value) {
                                    "0.0.0.0" -> listOf("127.0.0.1") + getIPv4Addresses()
                                    else -> listOf("127.0.0.1", serverConfig.ip.value)
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error getting IPv4 addresses" }
                                listOf("127.0.0.1")
                            }

                        logger.info {
                            val port = serverConfig.port.value
                            buildString {
                                appendLine("Server is available at:")

                                addresses
                                    .distinct()
                                    .forEach { address ->
                                        appendLine("  http://$address:$port")
                                    }
                            }.trimEnd()
                        }
                        if (serverConfig.initialOpenInBrowserEnabled.value) {
                            scope.launch {
                                withTimeoutOrNull(10.seconds) {
                                    WebInterfaceManager.isSetupComplete.first { it }
                                }
                                Browser.openInBrowser()
                            }
                        }
                    }
                }
            }

        app.jettyServer().server()
        return app
    }

    internal fun startPrepared(app: Javalin) {
        app.start()
        // when JVM is prompted to shutdown, stop javalin gracefully. GoManga:
        // install the process-scoped hook once and target the CURRENT app, so a
        // restart (which replaces `currentApp`) doesn't pile up stale hooks.
        currentApp = app
        if (!shutdownHookInstalled) {
            shutdownHookInstalled = true
            Runtime.getRuntime().addShutdownHook(
                thread(start = false) {
                    currentApp?.stop()
                },
            )
        }
    }

    fun RoutesConfig.defineCore() {
        // GoManga: defineCore runs during config-building (before the app is
        // started), so it cannot see javalinSetup's local `slimMode`; recompute it
        // from the same system property the rest of the slim wiring reads.
        val slimMode = System.getProperty("suwayomi.tachidesk.gomanga.slimMode", "false") == "true"
        val loginPath = ServerSubpath.maybeAddAsPrefix("/login.html")

        // The embedded loopback listener supports the SDK's NONE configuration.
        if (slimMode) {
            check(serverConfig.authMode.value == AuthMode.NONE) { "Unsupported authentication mode for embedded server" }
            beforeMatched { ctx ->
                if (serverConfig.authMode.value != AuthMode.NONE) throw UnauthorizedResponse()
                ctx.setAttribute(Attribute.TachideskUser, UserType.Admin(1))
                ctx.setAttribute(Attribute.TachideskBasic, false)
            }
            wsBefore {
                it.onConnect { ctx ->
                    if (serverConfig.authMode.value != AuthMode.NONE) {
                        ctx.closeSession(CloseStatus(1008, "Unsupported embedded authentication mode"))
                        return@onConnect
                    }
                    ctx.setAttribute(Attribute.TachideskUser, UserType.Admin(1))
                }
            }
        } else {
            get(loginPath) { ctx ->
                val locale: Locale = LocalizationHelper.ctxToLocale(ctx)
                ctx.header("content-type", "text/html")
                val httpCacheSeconds = 1.days.inWholeSeconds
                ctx.header("cache-control", "max-age=$httpCacheSeconds")
                ctx.render(
                    "Login.jte",
                    mapOf(
                        "locale" to locale,
                        "error" to "",
                    ),
                )
            }

            post(loginPath) { ctx ->
                val username = ctx.formParam("user")
                val password = ctx.formParam("pass")
                val isValid =
                    username == serverConfig.authUsername.value &&
                        password == serverConfig.authPassword.value

                if (isValid) {
                    val redirect = ctx.queryParam("redirect") ?: ServerSubpath.maybeAddAsPrefix("/")
                    val uri = URI(redirect)
                    if (uri.host != null || uri.scheme != null) {
                        throw IllegalArgumentException("Given redirect is not relative, refusing")
                    }
                    // NOTE: We currently have no session handler attached.
                    // Thus, all sessions are stored in memory and not persisted.
                    // Furthermore, default session timeout appears to be 30m
                    ctx.header("Location", redirect)
                    ctx.sessionAttribute("logged-in", username)
                    throw RedirectResponse(HttpStatus.SEE_OTHER)
                }

                val locale: Locale = LocalizationHelper.ctxToLocale(ctx)
                ctx.header("content-type", "text/html")
                ctx.req().session.invalidate()
                ctx.render(
                    "Login.jte",
                    mapOf(
                        "locale" to locale,
                        "error" to "Invalid username or password",
                    ),
                )
            }

            beforeMatched { ctx ->
                val isWebManifest =
                    listOf("site.webmanifest", "manifest.json", "login.html").any { ctx.path().endsWith(it) }
                val isPageIcon =
                    ctx.path().startsWith('/') &&
                        !ctx.path().substring(1).contains('/') &&
                        listOf(".png", ".jpg", ".ico").any { ctx.path().endsWith(it) }
                val isPreFlight = ctx.method() == HandlerType.OPTIONS
                val isApi = ctx.path().startsWith(ServerSubpath.maybeAddAsPrefix("/api/"))

                val requiresAuthentication = !isPreFlight && !isPageIcon && !isWebManifest
                if (!requiresAuthentication) {
                    return@beforeMatched
                }

                val authMode = serverConfig.authMode.value

                fun credentialsValid(): Boolean {
                    val basicAuthCredentials = ctx.basicAuthCredentials() ?: return false
                    val (username, password) = basicAuthCredentials
                    return username == serverConfig.authUsername.value &&
                        password == serverConfig.authPassword.value
                }

                fun cookieValid(): Boolean {
                    val username = ctx.sessionAttribute<String>("logged-in") ?: return false
                    return username == serverConfig.authUsername.value
                }

                if (authMode == AuthMode.SIMPLE_LOGIN && !cookieValid() && !isApi) {
                    val url =
                        "$loginPath?redirect=" + URLEncoder.encode(ctx.path() + (ctx.queryString()?.let { "?" + it } ?: ""), Charsets.UTF_8)
                    ctx.header("Location", url)
                    throw RedirectResponse(HttpStatus.SEE_OTHER)
                }

                if (authMode == AuthMode.BASIC_AUTH && !credentialsValid()) {
                    ctx.header("WWW-Authenticate", "Basic")
                    throw UnauthorizedResponse()
                }

                ctx.setAttribute(Attribute.TachideskUser, getUserFromContext(ctx))
                ctx.setAttribute(Attribute.TachideskBasic, credentialsValid())
            }

            wsBefore {
                it.onConnect { ctx ->
                    ctx.setAttribute(Attribute.TachideskUser, getUserFromWsContext(ctx))
                }
            }
        } // end !slimMode auth/login/ws wiring

        exception(NullPointerException::class.java) { e, ctx ->
            logger.error(e) { "NullPointerException while handling the request" }
            ctx.status(404)
        }
        exception(NoSuchElementException::class.java) { e, ctx ->
            logger.error(e) { "NoSuchElementException while handling the request" }
            ctx.status(404)
        }
        exception(IOException::class.java) { e, ctx ->
            logger.error(e) { "IOException while handling the request" }
            ctx.status(500)
            ctx.result(e.message ?: "Internal Server Error")
        }

        exception(IllegalArgumentException::class.java) { e, ctx ->
            logger.error(e) { "IllegalArgumentException while handling the request" }
            ctx.status(400)
            ctx.result(e.message ?: "Bad Request")
        }

        exception(UnauthorizedException::class.java) { e, ctx ->
            logger.error(e) { "UnauthorizedException while handling the request" }
            ctx.status(HttpStatus.UNAUTHORIZED)
            ctx.result(e.message ?: "Unauthorized")
        }

        exception(ForbiddenException::class.java) { e, ctx ->
            logger.error(e) { "ForbiddenException while handling the request" }
            ctx.status(HttpStatus.FORBIDDEN)
            ctx.result(e.message ?: "Forbidden")
        }

        exception(HttpException::class.java) { e, ctx ->
            if (e.code in 400..599) {
                logger.warn(e) { "GoManga source HTTP failure: ${e.code}" }
                ctx.status(HttpStatus.BAD_GATEWAY)
                ctx.json(GoMangaSourceHttpError(status = e.code))
            } else {
                logger.error(e) { "Invalid GoManga source HTTP status: ${e.code}" }
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
            }
        }
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

    // private fun getOpenApiOptions(): OpenApiOptions {
    //     val applicationInfo =
    //         Info().apply {
    //             version("1.0")
    //             description("Suwayomi-Server Api")
    //         }
    //     return OpenApiOptions(applicationInfo).apply {
    //         path("/api/openapi.json")
    //         swagger(
    //             SwaggerOptions("/api/swagger-ui").apply {
    //                 title("Suwayomi-Server Swagger Documentation")
    //             },
    //         )
    //     }
    // }

    sealed class Attribute<T : Any>(
        val name: String,
    ) {
        data object TachideskUser : Attribute<UserType>("user")

        data object TachideskBasic : Attribute<Boolean>("basicAuthValid")
    }

    private fun <T : Any> Context.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    private fun <T : Any> WsContext.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    fun <T : Any> Context.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttributeOrSet(
        attribute: Attribute<T>,
        replaceIf: (T) -> Boolean = { false },
        set: () -> T,
    ): T {
        var item: T? = attribute(attribute.name)

        if (item != null && replaceIf(item)) {
            item = null
        }

        return item ?: set().also { setAttribute(attribute, it) }
    }

    fun getIPv4Addresses(): List<String> =
        NetworkInterface
            .getNetworkInterfaces()
            .asSequence()
            .flatMap { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@flatMap emptySequence()
                }

                networkInterface.inetAddresses
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { address -> address.hostAddress }
            }.toList()
}
