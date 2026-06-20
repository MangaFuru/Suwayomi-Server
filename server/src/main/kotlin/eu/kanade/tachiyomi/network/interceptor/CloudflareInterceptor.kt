package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class CloudflareInterceptor(
    private val setUserAgent: (String) -> Unit,
) : Interceptor {
    private val logger = KotlinLogging.logger {}

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        logger.trace { "CloudflareInterceptor is being used." }

        val originalResponse = chain.proceed(originalRequest)

        // GoMangaConvergeFirst: on a Cloudflare wall, try the FlareSolverr/shim
        // path FIRST. It adopts the cf_clearance cookies + the clearance UA into
        // the server's OWN OkHttp jar (cookieStore.addAll + setUserAgent) and
        // retries the request DIRECTLY — so once a host is solved, subsequent
        // requests in the session go direct instead of paying the WebView-proxy
        // double-hop on every call (the prior proxy-first order short-circuited
        // before this adoption ever ran, so the jar/UA never converged). The
        // WebView proxy stays as the LAST resort below.
        val isCloudflareWall =
            originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK
        val originalCode = originalResponse.code
        if (isCloudflareWall && serverConfig.flareSolverrEnabled.value) {
            logger.debug { "Cloudflare anti-bot is on, trying direct cookie/UA adoption first..." }
            try {
                val request =
                    runBlocking {
                        CFClearance.resolveWithFlareSolverr(setUserAgent, originalRequest)
                    }
                val retried = chain.proceed(request)
                // If the direct retry (now carrying the adopted cf_clearance + UA)
                // is NO LONGER a Cloudflare wall, convergence worked — return it and
                // the host stays on the cheap direct path for the rest of the
                // session. BUT some sources (e.g. happymh) gate behind a
                // browser-TLS / path-scoped challenge that the JVM OkHttp client
                // canNOT pass even WITH a valid clearance cookie + UA — the direct
                // retry stays 403/Cloudflare. In that case do NOT return the 403;
                // fall through to the WebView proxy (Safari TLS + same-origin
                // context), which is the ONLY path that clears those sources. (The
                // prior code returned the direct 403 here, which left those sources
                // permanently stuck — the proxy never ran.)
                val stillWalled =
                    retried.code in ERROR_CODES && retried.header("Server") in SERVER_CHECK
                if (!stillWalled) {
                    originalResponse.close()
                    return retried
                }
                logger.debug { "Direct adoption still walled (code=${retried.code}); falling back to the WebView proxy" }
                retried.close()
            } catch (e: Exception) {
                // The shim has no clearance for this host yet, or the bypass
                // failed (CloudflareBypassException / transport). Do NOT propagate
                // — fall through to the WebView proxy, which can solve the
                // challenge interactively and flag the host for user verification.
                logger.debug(e) { "Direct adoption failed; falling back to the WebView proxy" }
            }
        }

        // WebView proxy fallback. Reached when direct adoption was not tried (non-CF
        // 400/429 walls), failed, or — crucially — succeeded at adopting cookies but
        // the source STILL walls the JVM client (browser-TLS-gated sources like
        // happymh). This is the last resort and the only path those sources clear.
        runBlocking {
            CFClearance.resolveWithGoMangaProxy(originalRequest, originalCode)
        }?.let { proxyResponse ->
            originalResponse.close()
            return proxyResponse
        }

        // Not a Cloudflare wall and the proxy declined → return the response as-is.
        if (!isCloudflareWall) {
            return originalResponse
        }

        if (!serverConfig.flareSolverrEnabled.value) {
            throw IOException("Cloudflare bypass currently disabled")
        }

        // A Cloudflare wall that neither direct adoption nor the proxy could clear.
        // OkHttp's enqueue only handles IOExceptions, so surface one (don't crash).
        originalResponse.close()
        throw IOException("Cloudflare challenge could not be resolved")
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        private val COOKIE_NAMES = listOf("cf_clearance")
    }
}

/*
 * This class is ported from https://github.com/vvanglro/cf-clearance
 * The original code is licensed under Apache 2.0
*/
object CFClearance {
    private val logger = KotlinLogging.logger {}
    private val network: NetworkHelper by injectLazy()
    private val client by lazy {
        @Suppress("OPT_IN_USAGE")
        serverConfig.flareSolverrTimeout
            .map { timeoutInt ->
                val timeout = timeoutInt.seconds
                network.client.newBuilder()
                    .callTimeout(timeout.plus(10.seconds).toJavaDuration())
                    .readTimeout(timeout.plus(5.seconds).toJavaDuration())
                    .build()
            }
            .stateIn(GlobalScope, SharingStarted.Eagerly, network.client)
    }
    private val json: Json by injectLazy()
    private val jsonMediaType = "application/json".toMediaType()
    private val mutex = Mutex()

    @Serializable
    data class FlareSolverCookie(
        val name: String,
        val value: String,
    )

    @Serializable
    data class FlareSolverRequest(
        val cmd: String,
        val url: String,
        val maxTimeout: Int? = null,
        val session: String? = null,
        @SerialName("session_ttl_minutes")
        val sessionTtlMinutes: Int? = null,
        val cookies: List<FlareSolverCookie>? = null,
        val returnOnlyCookies: Boolean? = null,
        val proxy: String? = null,
        val postData: String? = null, // only used with cmd 'request.post'
    )

    @Serializable
    data class FlareSolverSolutionCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expires: Double? = null,
        val size: Int? = null,
        val httpOnly: Boolean,
        val secure: Boolean,
        val session: Boolean? = null,
        val sameSite: String,
    )

    @Serializable
    data class FlareSolverSolution(
        val url: String,
        val status: Int,
        val headers: Map<String, String>? = null,
        val response: String? = null,
        val cookies: List<FlareSolverSolutionCookie>,
        val userAgent: String,
    )

    @Serializable
    data class FlareSolverResponse(
        val solution: FlareSolverSolution,
        val status: String,
        val message: String,
        val startTimestamp: Long,
        val endTimestamp: Long,
        val version: String,
    )

    @Serializable
    data class GoMangaProxyRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    @Serializable
    data class GoMangaProxyResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    suspend fun resolveWithGoMangaProxy(
        originalRequest: Request,
        responseCode: Int,
    ): Response? {
        if (!shouldAttemptGoMangaProxy(originalRequest, responseCode)) {
            return null
        }

        val requestBody = originalRequest.textBodyOrNull()
        if (originalRequest.body != null && requestBody == null) {
            return null
        }

        val proxyUrl = serverConfig.flareSolverrUrl.value.removeSuffix("/") + "/gomanga-proxy"
        val proxyResponse =
            try {
                with(json) {
                    client.value.newCall(
                        POST(
                            url = proxyUrl,
                            body =
                                Json.encodeToString(
                                    GoMangaProxyRequest(
                                        method = originalRequest.method,
                                        url = originalRequest.url.toString(),
                                        headers = originalRequest.headers.asMap(),
                                        body = requestBody,
                                    ),
                                ).toRequestBody(jsonMediaType),
                        ),
                    ).awaitSuccess().parseAs<GoMangaProxyResponse>()
                }
            } catch (e: Exception) {
                logger.debug(e) { "GoManga WebView proxy fallback failed for ${originalRequest.url}" }
                return null
            }

        if (proxyResponse.status !in 200..399) {
            logger.debug {
                "GoManga WebView proxy returned ${proxyResponse.status} for ${originalRequest.url}; keeping original response"
            }
            return null
        }

        logger.debug { "GoManga WebView proxy served ${originalRequest.method} ${originalRequest.url}" }
        return proxyResponse.toOkHttpResponse(originalRequest)
    }

    suspend fun resolveWithFlareSolverr(
        setUserAgent: (String) -> Unit,
        originalRequest: Request,
    ): Request {
        val timeout = serverConfig.flareSolverrTimeout.value.seconds
        val flareSolverResponse =
            with(json) {
                mutex.withLock {
                    client.value.newCall(
                        POST(
                            url = serverConfig.flareSolverrUrl.value.removeSuffix("/") + "/v1",
                            body =
                                Json.encodeToString(
                                    FlareSolverRequest(
                                        "request.get",
                                        originalRequest.url.toString(),
                                        session = serverConfig.flareSolverrSessionName.value,
                                        sessionTtlMinutes = serverConfig.flareSolverrSessionTtl.value,
                                        cookies =
                                            network.cookieStore.get(originalRequest.url).map {
                                                FlareSolverCookie(it.name, it.value)
                                            },
                                        returnOnlyCookies = true,
                                        maxTimeout = timeout.inWholeMilliseconds.toInt(),
                                    ),
                                ).toRequestBody(jsonMediaType),
                        ),
                    ).awaitSuccess().parseAs<FlareSolverResponse>()
                }
            }

        if (flareSolverResponse.solution.status in 200..299) {
            setUserAgent(flareSolverResponse.solution.userAgent)
            val cookies =
                flareSolverResponse.solution.cookies
                    .map { cookie ->
                        Cookie.Builder()
                            .name(cookie.name)
                            .value(cookie.value)
                            .domain(cookie.domain.removePrefix("."))
                            .path(cookie.path)
                            .expiresAt(cookie.expires?.takeUnless { it < 0.0 }?.toLong() ?: Long.MAX_VALUE)
                            .also {
                                if (cookie.httpOnly) it.httpOnly()
                                if (cookie.secure) it.secure()
                            }
                            .build()
                    }
                    .groupBy { it.domain }
                    .flatMap { (domain, cookies) ->
                        network.cookieStore.addAll(
                            HttpUrl.Builder()
                                .scheme("http")
                                .host(domain.removePrefix("."))
                                .build(),
                            cookies,
                        )

                        cookies
                    }
            logger.trace { "New cookies\n${cookies.joinToString("; ")}" }
            val finalCookies =
                network.cookieStore.get(originalRequest.url).joinToString("; ", postfix = "; ") {
                    "${it.name}=${it.value}"
                }
            logger.trace { "Final cookies\n$finalCookies" }
            return originalRequest.newBuilder()
                .header("Cookie", finalCookies)
                .header("User-Agent", flareSolverResponse.solution.userAgent)
                .build()
        } else {
            logger.debug { "Cloudflare challenge failed to resolve" }
            throw CloudflareBypassException()
        }
    }

    private fun shouldAttemptGoMangaProxy(
        request: Request,
        responseCode: Int,
    ): Boolean {
        if (!serverConfig.flareSolverrEnabled.value) {
            return false
        }

        if (responseCode !in listOf(400, 403, 429, 503)) {
            return false
        }

        if (request.method !in listOf("GET", "POST")) {
            return false
        }

        val host = request.url.host.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            return false
        }

        val accept = request.header("Accept")?.lowercase().orEmpty()
        if (accept.contains("image/") || accept.contains("video/")) {
            return false
        }

        val bodyType = request.body?.contentType()
        if (bodyType != null && !bodyType.isTextLike()) {
            return false
        }

        return true
    }

    private fun Request.textBodyOrNull(): String? {
        val requestBody = body ?: return null
        val type = requestBody.contentType()
        if (type != null && !type.isTextLike()) {
            return null
        }

        val buffer = Buffer()
        requestBody.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun okhttp3.MediaType.isTextLike(): Boolean {
        val major = type.lowercase()
        val subtype = subtype.lowercase()
        return major == "text" ||
            subtype.contains("json") ||
            subtype.contains("xml") ||
            subtype.contains("form")
    }

    private fun okhttp3.Headers.asMap(): Map<String, String> =
        names().associateWith { name -> get(name).orEmpty() }

    private fun GoMangaProxyResponse.toOkHttpResponse(originalRequest: Request): Response {
        val contentType =
            headers.entries
                .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
                ?.value
                ?: "text/plain; charset=utf-8"
        val responseBuilder =
            Response.Builder()
                .request(originalRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("GoManga WebView proxy")
                .body(body.toResponseBody(contentType.toMediaTypeOrNull()))

        headers.forEach { (name, value) ->
            if (!name.equals("content-length", ignoreCase = true)) {
                try {
                    responseBuilder.header(name, value)
                } catch (_: IllegalArgumentException) {
                    logger.debug { "Skipping invalid proxied response header: $name" }
                }
            }
        }

        return responseBuilder.build()
    }

    private class CloudflareBypassException : Exception()
}
