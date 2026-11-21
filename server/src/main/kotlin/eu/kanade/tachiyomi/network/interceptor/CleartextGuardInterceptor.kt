package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * GoMangaInsecureHttpGate: blocks non-loopback cleartext http egress from the
 * source-fetch OkHttp client when the app-side InsecureSourcePolicy
 * ("允許不安全的來源") is OFF. This is the JVM-internal half of that policy —
 * it closes the residuals the app's URLSession gate can never see because the
 * request originates and terminates inside the embedded server's OkHttp:
 *   1. a requiresProxy page image the JVM fetches itself (no app-direct URL),
 *   2. an https->http redirect downgrade OkHttp follows internally,
 *   3. an https source's extension calling an http endpoint directly.
 *
 * Register the SAME instance BOTH as an application interceptor
 * (addInterceptor) AND a network interceptor (addNetworkInterceptor). The two
 * invocations are told apart by chain.connection():
 *   - application invocation (connection() == null): sees the ORIGINAL request
 *     url before any socket is opened -> synthesize a 496 short-circuit, no
 *     connection. Catches residuals #1 / #3 cleanly (nothing dials out).
 *   - network invocation (connection() != null): fires once per redirect/retry
 *     hop with the ACTUAL hop url -> MUST throw (a network interceptor that
 *     returns a synthetic response trips OkHttp's "network interceptor must
 *     call proceed() exactly once"). Catches residual #2, the downgraded hop.
 *
 * When the policy is ON, isAllowed() is true for every url on both invocations
 * -> chain.proceed(request) unconditionally, i.e. a PURE no-op (no synthetic
 * response, no throw, zero behavior change for a working http source).
 *
 * The loopback exemption is kept byte-identical to the app-side
 * InsecureSourcePolicy.isLoopbackHost and CloudflareInterceptor's own loopback
 * check so the embedded server's own 127.0.0.1 traffic and the in-app
 * FlareSolverr/CF WebView proxy (localhost) are never gated.
 */
class CleartextGuardInterceptor(
    private val allowInsecureHttp: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isAllowed(request.url)) {
            return chain.proceed(request)
        }
        return if (chain.connection() == null) {
            // Application invocation: no socket yet -> short-circuit.
            blockedResponse(request)
        } else {
            // Network invocation (incl. a redirect-downgraded hop): a synthetic
            // response is illegal here, so refuse the hop by throwing.
            throw IOException(
                "$GATE_MARKER blocked cleartext http to ${request.url.host}",
            )
        }
    }

    private fun isAllowed(url: HttpUrl): Boolean =
        url.isHttps || isLoopbackHost(url.host) || allowInsecureHttp()

    private fun isLoopbackHost(host: String): Boolean =
        when (host.lowercase()) {
            "127.0.0.1", "localhost", "::1" -> true
            else -> false
        }

    private fun blockedResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(INSECURE_BLOCKED_CODE)
            .message("Insecure cleartext blocked")
            .header("X-GoManga-Insecure-Blocked", "1")
            // Carry the marker in the BODY, not just an empty 496. Sources that swallow
            // a failed request into a decode/parse error (e.g. manhuaren's fetchToken →
            // Json.decodeFromString(body) → "JSON input: <body>") then propagate the
            // marker verbatim, so the app-side SourceErrorPresentation classifier can
            // still recognise it as an insecure-cleartext block and offer the
            // 允許不安全來源 CTA — instead of an opaque EOF the user can't act on.
            .body("$GATE_MARKER blocked cleartext http to ${request.url.host}".toResponseBody(null))
            .build()

    companion object {
        /** Non-standard status used for a policy-blocked cleartext request. */
        const val INSECURE_BLOCKED_CODE = 496

        /**
         * Build marker greppable in source AND in the shipped bytecode strings,
         * wired into 20b-build-suwayomi-from-source.sh so a silently-non-landing
         * change fails the build instead of shipping stale bytecode.
         */
        const val GATE_MARKER = "GoMangaInsecureHttpGate"
    }
}
