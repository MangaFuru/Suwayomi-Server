package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class CloudflareInterceptor(
    private val setUserAgent: (String) -> Unit,
    private val sourceID: (() -> String?)? = null,
) : Interceptor {
    private val logger = KotlinLogging.logger {}
    private val network: NetworkHelper by injectLazy()
    private val sourceNetwork: NetworkHelper by lazy {
        sourceID?.let { network.forSource(it) } ?: network
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        logger.trace { "CloudflareInterceptor is being used." }

        val originalResponse = chain.proceed(originalRequest)

        // Check if Cloudflare anti-bot is on
        // October recovery: downstream source interceptors and redirects may
        // change this request. The response owns the actual failed hop.
        val effectiveFailedRequest = originalResponse.request
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK)) {
            // A browser session can recover an ordinary denial without identifying
            // a verification vendor, page, or cookie name. Do not create a browser
            // session here: the host only replays an already captured session.
            val replay = runBlocking {
                CFClearance.resolveWithGoMangaProxy(effectiveFailedRequest, originalResponse.code, browserSessionOnly = true, sourceNetwork = sourceNetwork)
            }
            if (replay != null) {
                logger.debug { "GoMangaBrowserSessionReplay: recovered rejected request" }
                originalResponse.close()
                return replay
            }
            return originalResponse
        }

        if (!serverConfig.flareSolverrEnabled.value) {
            throw IOException("Cloudflare bypass currently disabled")
        }

        logger.debug { "Cloudflare anti-bot is on, CloudflareInterceptor is kicking in..." }

        // GoMangaConvergeFirst: try the direct source session before the browser proxy.
        val flareResponseFallback = serverConfig.flareSolverrAsResponseFallback.value
        val originalCode = originalResponse.code
        originalResponse.close()

        try {
            val retried = resolveCloudflare(chain, effectiveFailedRequest, originalResponse, flareResponseFallback)
            if (!(retried.code in ERROR_CODES && retried.header("Server") in SERVER_CHECK)) {
                return retried
            }
            retried.close()
        } catch (e: Exception) {
            logger.debug(e) { "Direct challenge recovery failed" }
        }

        try {
            runBlocking {
                CFClearance.resolveWithGoMangaProxy(effectiveFailedRequest, originalCode, sourceNetwork = sourceNetwork)
            }?.let { return it }
        } catch (e: Exception) {
            throw IOException(e)
        }
        throw IOException("Cloudflare challenge could not be resolved")
    }

    private fun resolveCloudflare(
        chain: Interceptor.Chain,
        originalRequest: Request,
        originalResponse: Response,
        flareResponseFallback: Boolean,
    ): Response {
        val host = originalRequest.url.host
        val partition = CFClearance.ChallengePartition(
            host,
            sourceNetwork.sourceIDOrNull(),
            originalRequest.header("User-Agent"),
        )

        while (true) {
            val bypassRequest = CompletableFuture<CFClearance.Result>()
            val inflightRequest = CFClearance.inflightCalls.putIfAbsent(partition, bypassRequest)

            val awaitInflightResult = inflightRequest != null
            if (awaitInflightResult) {
                logger.debug { "Waiting for inflight call for host $host" }

                when (val result = awaitInflightResult(inflightRequest)) {
                    is CFClearance.Result.CloudflareBypassed -> {
                        val request =
                            CFClearance.buildRequestWithStoredCookies(
                                originalRequest,
                                result.userAgent,
                                sourceNetwork = sourceNetwork,
                            )

                        return chain.proceed(request)
                    }

                    is CFClearance.Result.CloudflareNotDetected -> {
                        logger.debug { "Inflight call did not detect Cloudflare for $host, retrying" }
                        continue
                    }
                }
            }

            logger.debug { "Calling FlareSolverr for host $host" }
            try {
                val flareResponse =
                    runBlocking {
                        CFClearance.resolveWithFlareSolver(originalRequest, !flareResponseFallback, sourceNetwork = sourceNetwork)
                    }

                val cloudflareDetected =
                    !flareResponse.message.contains("not detected", ignoreCase = true)
                return if (cloudflareDetected) {
                    val request =
                        CFClearance.requestWithFlareSolverr(
                            flareResponse,
                            setUserAgent,
                            originalRequest,
                            sourceNetwork = sourceNetwork,
                        )
                    bypassRequest.complete(
                        CFClearance.Result.CloudflareBypassed(
                            flareResponse.solution.userAgent,
                        ),
                    )

                    chain.proceed(request)
                } else {
                    CFClearance.inflightCalls.remove(partition, bypassRequest)
                    bypassRequest.complete(CFClearance.Result.CloudflareNotDetected)

                    maybeFallbackToFlareSolverResponse(
                        flareResponse,
                        chain,
                        originalRequest,
                        originalResponse,
                        flareResponseFallback,
                    )
                }
            } catch (e: Exception) {
                bypassRequest.completeExceptionally(e)
                throw e
            } finally {
                CFClearance.inflightCalls.remove(partition, bypassRequest)
            }
        }
    }

    private fun maybeFallbackToFlareSolverResponse(
        flareResponse: CFClearance.FlareSolverResponse,
        chain: Interceptor.Chain,
        originalRequest: Request,
        originalResponse: Response,
        flareResponseFallback: Boolean,
    ): Response {
        logger.debug { "FlareSolverr failed to detect Cloudflare challenge" }

        if (flareResponseFallback &&
            flareResponse.solution.status in 200..299 &&
            flareResponse.solution.response != null
        ) {
            val isImage =
                flareResponse.solution.response.contains(CHROME_IMAGE_TEMPLATE_REGEX)
            if (!isImage) {
                logger.debug { "Falling back to FlareSolverr response" }

                setUserAgent(flareResponse.solution.userAgent)

                return originalResponse
                    .newBuilder()
                    .code(flareResponse.solution.status)
                    .body(flareResponse.solution.response.toResponseBody())
                    .build()
            } else {
                logger.debug { "FlareSolverr response is an image html template, not falling back" }
            }
        }

        val request =
            CFClearance.requestWithFlareSolverr(flareResponse, setUserAgent, originalRequest, sourceNetwork = sourceNetwork)

        return chain.proceed(request)
    }

    private fun awaitInflightResult(future: CompletableFuture<CFClearance.Result>): CFClearance.Result {
        while (true) {
            try {
                return future.get()
            } catch (_: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        val COOKIE_NAMES = listOf("cf_clearance")
        private val CHROME_IMAGE_TEMPLATE_REGEX = Regex("""<title>(.*?) \(\d+×\d+\)</title>""")
    }
}


/*
 * This class is ported from https://github.com/vvanglro/cf-clearance
 * The original code is licensed under Apache 2.0
*/
object CFClearance {
    private val logger = KotlinLogging.logger {}
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()
    private val jsonMediaType = "application/json".toMediaType()
    private val mutex = Mutex()

    sealed class Result {
        data class CloudflareBypassed(
            val userAgent: String,
        ) : Result()

        data object CloudflareNotDetected : Result()
    }

    data class ChallengePartition(
        val host: String,
        val sourceId: String?,
        val userAgent: String?,
    )

    val inflightCalls = ConcurrentHashMap<ChallengePartition, CompletableFuture<Result>>()

    fun buildRequestWithStoredCookies(
        request: Request,
        userAgent: String,
        sourceNetwork: NetworkHelper,
    ): Request {
        val cookies =
            sourceNetwork.cookieStore.get(request.url)
                .filter { it.name !in CloudflareInterceptor.COOKIE_NAMES }
                .joinToString("; ", postfix = "; ") { "${it.name}=${it.value}" }

        val explicitUserAgent = request.header("User-Agent")
        if (explicitUserAgent != null && explicitUserAgent != userAgent) {
            logger.debug { "Cloudflare clearance UA does not match explicit source UA" }
            throw CloudflareBypassException()
        }
        return request
            .newBuilder()
            .header("Cookie", cookies)
            .apply {
                if (explicitUserAgent == null) {
                    header("User-Agent", userAgent)
                    tag(
                        UserAgentInterceptor.Clearance::class.java,
                        UserAgentInterceptor.Clearance(request.url.host, userAgent),
                    )
                }
            }.build()
    }

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
        val sourceId: String? = null,
        val userAgent: String? = null,
    )

    @Serializable
    data class FlareSolverSolutionCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String? = null,
        val expires: Double? = null,
        val size: Int? = null,
        val httpOnly: Boolean? = null,
        val secure: Boolean? = null,
        val session: Boolean? = null,
        val sameSite: String? = null,
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
    data class GoMangaOperationID(val value: String)

    @Serializable
    data class GoMangaEffectiveRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    @Serializable
    data class GoMangaProxyRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
        val browserSessionOnly: Boolean = false,
        // Optional October bridge fields. Defaults preserve older SDK peers.
        val recoveryVersion: Int? = null,
        val operationID: String? = null,
        val effectiveRequest: GoMangaEffectiveRequest? = null,
        val uaOrigin: String? = null,
        val sourceId: String? = null,
    )

    @Serializable
    data class GoMangaProxyResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
        // Absent from older iOS SDKs. Keep nullable defaults so a rebuilt runtime
        // accepts legacy proxy envelopes and retains the original request metadata.
        val finalUrl: String? = null,
        val finalMethod: String? = null,
    )

    suspend fun resolveWithGoMangaProxy(
        originalRequest: Request,
        responseCode: Int,
        browserSessionOnly: Boolean = false,
        sourceNetwork: NetworkHelper = network,
    ): Response? {
        if (browserSessionOnly && responseCode != 403) return null
        if (!shouldAttemptGoMangaProxy(originalRequest, responseCode)) {
            return null
        }

        if (originalRequest.body?.let { it.isOneShot() || it.isDuplex() } == true) return null
        val requestBody = originalRequest.textBodyOrNull()
        if (originalRequest.body != null && requestBody == null) {
            return null
        }

        val proxyUrl = serverConfig.flareSolverrUrl.value.removeSuffix("/") + "/gomanga-proxy"
        val proxyResponse =
            try {
                with(json) {
                    solverClient(sourceNetwork)
                        .newCall(
                            POST(
                                url = proxyUrl,
                                body =
                                    Json
                                        .encodeToString(
                                            GoMangaProxyRequest(
                                                method = originalRequest.method,
                                                url = originalRequest.url.toString(),
                                                headers = originalRequest.headers.asMap(),
                                                body = requestBody,
                                                browserSessionOnly = browserSessionOnly,
                                                recoveryVersion = 1,
                                                operationID = originalRequest.tag(GoMangaOperationID::class.java)?.value,
                                                effectiveRequest = originalRequest.toGoMangaEffectiveRequest(requestBody),
                                                uaOrigin = originalRequest.uaOrigin(),
                                                sourceId = sourceNetwork.sourceIDOrNull(),
                                            ),
                                        ).toRequestBody(jsonMediaType),
                            ),
                        ).awaitSuccess()
                        .parseAs<GoMangaProxyResponse>()
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

    private fun solverClient(sourceNetwork: NetworkHelper): okhttp3.OkHttpClient =
        sourceNetwork.client
            .newBuilder()
            .callTimeout(serverConfig.flareSolverrTimeout.value.seconds.plus(10.seconds).toJavaDuration())
            .readTimeout(serverConfig.flareSolverrTimeout.value.seconds.plus(5.seconds).toJavaDuration())
            .build()

    suspend fun resolveWithFlareSolver(
        originalRequest: Request,
        onlyCookies: Boolean,
        sourceNetwork: NetworkHelper = network,
    ): FlareSolverResponse {
        val timeout = serverConfig.flareSolverrTimeout.value.seconds
        return with(json) {
            mutex.withLock {
                solverClient(sourceNetwork)
                    .newCall(
                        POST(
                            url = serverConfig.flareSolverrUrl.value.removeSuffix("/") + "/v1",
                            body =
                                Json
                                    .encodeToString(
                                        FlareSolverRequest(
                                            "request.${originalRequest.method.lowercase()}",
                                            originalRequest.url.toString(),
                                            session = serverConfig.flareSolverrSessionName.value,
                                            sessionTtlMinutes = serverConfig.flareSolverrSessionTtl.value,
                                            cookies =
                                                sourceNetwork.cookieStore
                                                    .get(originalRequest.url)
                                                    .filter { it.name !in CloudflareInterceptor.COOKIE_NAMES }
                                                    .map { cookie ->
                                                        FlareSolverCookie(cookie.name, cookie.value)
                                                    },
                                            returnOnlyCookies = onlyCookies,
                                            maxTimeout = timeout.inWholeMilliseconds.toInt(),
                                            userAgent = originalRequest.header("User-Agent"),
                                            postData =
                                                if (originalRequest.method == "POST") {
                                                    originalRequest.body
                                                        ?.let { body ->
                                                            Buffer()
                                                                .also { body.writeTo(it) }
                                                                .readUtf8()
                                                        }.orEmpty()
                                                } else {
                                                    null
                                                },
                                            sourceId = sourceNetwork.sourceIDOrNull(),
                                        ),
                                    ).toRequestBody(jsonMediaType),
                        ),
                    ).awaitSuccess()
                    .parseAs<FlareSolverResponse>()
            }
        }
    }

    fun requestWithFlareSolverr(
        flareSolverResponse: FlareSolverResponse,
        setUserAgent: (String) -> Unit,
        originalRequest: Request,
        sourceNetwork: NetworkHelper = network,
    ): Request {
        if (flareSolverResponse.solution.cookies.none { it.name in CloudflareInterceptor.COOKIE_NAMES }) {
            logger.debug { "Cloudflare challenge failed to resolve" }
            throw CloudflareBypassException()
        }
        val solvedUserAgent = flareSolverResponse.solution.userAgent
        val retry = buildRequestWithStoredCookies(originalRequest, solvedUserAgent, sourceNetwork)
        setUserAgent(solvedUserAgent)
        val cookies =
            flareSolverResponse.solution.cookies
                .map { cookie ->
                    Cookie
                        .Builder()
                        .name(cookie.name)
                        .value(cookie.value)
                        .domain(cookie.domain.removePrefix("."))
                        .also {
                            if (cookie.httpOnly != null && cookie.httpOnly) it.httpOnly()
                            if (cookie.secure != null && cookie.secure) it.secure()
                            if (!cookie.path.isNullOrEmpty()) it.path(cookie.path)
                            // We need to convert the expires time to milliseconds for the persistent cookie store
                            if (cookie.expires != null && cookie.expires > 0) it.expiresAt((cookie.expires * 1000).toLong())
                            if (!cookie.domain.startsWith('.')) {
                                it.hostOnlyDomain(cookie.domain.removePrefix("."))
                            }
                        }.build()
                }
        val browserCookies = cookies.filter { it.name in CloudflareInterceptor.COOKIE_NAMES }
        if (browserCookies.isNotEmpty()) {
            sourceNetwork.replaceBrowserClearance(
                originalRequest.url.host,
                browserCookies,
                flareSolverResponse.solution.userAgent,
            )
        }
        return retry
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
        names()
            .associateWith { name -> get(name).orEmpty() }

    private fun okhttp3.Headers.effectiveMap(): Map<String, String> =
        names()
            .filterNot { name ->
                val connectionNominated = get("Connection")
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                name.equals("Connection", true) || connectionNominated.any { it.equals(name, true) } ||
                name.equals("Keep-Alive", true) ||
                name.equals("Proxy-Authenticate", true) || name.equals("Proxy-Authorization", true) ||
                name.equals("TE", true) || name.equals("Trailer", true) ||
                name.equals("Transfer-Encoding", true) || name.equals("Upgrade", true) }
            .associateWith { name -> get(name).orEmpty() }

    private fun Request.toGoMangaEffectiveRequest(bodyText: String?): GoMangaEffectiveRequest =
        GoMangaEffectiveRequest(
            url = url.toString(),
            method = method,
            headers = headers.effectiveMap(),
            body = bodyText,
        )

    private fun Request.uaOrigin(): String =
        tag(UserAgentInterceptor.Provenance::class.java)?.kind ?: "unknown"


    private fun GoMangaProxyResponse.toOkHttpResponse(originalRequest: Request): Response {
        val responseRequest = finalRequestMetadataOrOriginal(originalRequest)
        val contentType =
            headers.entries
                .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
                ?.value
                ?: "text/plain; charset=utf-8"
        val responseBuilder =
            Response
                .Builder()
                .request(responseRequest)
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

    /**
     * GoMangaProxyFinalRequestMetadata: report the browser's terminal request to
     * extensions that inspect Response.request after a redirected browser replay.
     * Invalid/legacy metadata deliberately leaves the original request untouched.
     */
    private fun GoMangaProxyResponse.finalRequestMetadataOrOriginal(originalRequest: Request): Request {
        val finalRequestUrl =
            finalUrl
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toHttpUrlOrNull()
                ?.takeIf { url ->
                    (url.scheme == "http" || url.scheme == "https") &&
                        url.username.isEmpty() &&
                        url.password.isEmpty()
                }
                ?: return originalRequest
        val finalRequestMethod =
            finalMethod
                ?.uppercase()
                ?.takeIf { it in setOf("GET", "POST", "HEAD") }
                ?: return originalRequest
        val sameOrigin = originalRequest.url.sameOriginAs(finalRequestUrl)

        // URLSession reports POST redirects only when it preserved the request
        // method. Never synthesize a body, and never carry a same-origin body to a
        // different origin even when a server requested a 307/308-style redirect.
        val finalBody =
            when (finalRequestMethod) {
                "POST" -> if (sameOrigin && originalRequest.method == "POST") originalRequest.body else null
                "GET", "HEAD" -> null
                else -> null
            }
        if (finalRequestMethod == "POST" && finalBody == null) return originalRequest

        if (!sameOrigin) {
            return Request
                .Builder()
                .url(finalRequestUrl)
                .method(finalRequestMethod, null)
                .apply {
                    listOf("Accept", "Accept-Language", "User-Agent").forEach { name ->
                        originalRequest.header(name)?.let { header(name, it) }
                    }
                    // A redirect may retain only an existing, valid HTTP(S)
                    // Referer, reduced to its origin. Never manufacture one from
                    // the request URL or preserve a path/query/userinfo value.
                    originalRequest
                        .header("Referer")
                        ?.trim()
                        ?.toHttpUrlOrNull()
                        ?.takeIf { url -> url.scheme == "http" || url.scheme == "https" }
                        ?.let { referer -> header("Referer", referer.originReferer()) }
                }.build()
        }

        return originalRequest
            .newBuilder()
            .url(finalRequestUrl)
            .method(finalRequestMethod, finalBody)
            .apply {
                if (finalRequestMethod == "GET" || finalRequestMethod == "HEAD") {
                    removeHeader("Content-Length")
                    removeHeader("Content-Type")
                    removeHeader("Transfer-Encoding")
                }
            }.build()
    }

    private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun HttpUrl.originReferer(): String =
        newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .username("")
            .password("")
            .build()
            .toString()

    private class CloudflareBypassException : Exception()
}
