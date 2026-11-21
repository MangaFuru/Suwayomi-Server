package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
    private val userAgentProvider: () -> String,
) : Interceptor {
    // GoMangaSourceUserAgent: preserve an explicit source UA; clearance
    // replay is accepted only when it matches that explicit identity.
    data class Clearance(val host: String, val userAgent: String)
    data class Provenance(val kind: String, val userAgent: String)


    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val replay = originalRequest.tag(Clearance::class.java)
        // The tag marks a UA synthesized by our replay, not source provenance.
        // A redirect must not carry that identity to an unrelated origin.
        if (replay != null && !replay.host.equals(originalRequest.url.host, ignoreCase = true) &&
            originalRequest.header("User-Agent") == replay.userAgent) {
            return chain.proceed(originalRequest.newBuilder()
                .header("User-Agent", userAgentProvider())
                .tag(Clearance::class.java, null)
                .build())
        }
        if (originalRequest.header("User-Agent") != null) {
            val headerUserAgent = originalRequest.header("User-Agent").orEmpty()
            val prior = originalRequest.tag(Provenance::class.java)
            val provenance = when {
                prior?.kind == "baseline" && prior.userAgent != headerUserAgent ->
                    Provenance("source", headerUserAgent)
                prior?.kind == "baseline" ->
                    // Equal values do not prove that the source did not author
                    // the header after the app baseline interceptor ran.
                    Provenance("unknown", headerUserAgent)
                else -> prior ?: Provenance("source", headerUserAgent)
            }
            return chain.proceed(originalRequest.newBuilder().tag(Provenance::class.java, provenance).build())
        }
        val clearance = originalRequest.tag(Clearance::class.java)
            ?.takeIf { it.host.equals(originalRequest.url.host, ignoreCase = true) && it.userAgent.isNotBlank() }
        val userAgent = clearance?.userAgent ?: userAgentProvider()
        return chain.proceed(
            originalRequest.newBuilder()
                .header("User-Agent", userAgent)
                .tag(Provenance::class.java, Provenance("baseline", userAgent))
                .build(),
        )
    }
}
