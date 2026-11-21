package eu.kanade.tachiyomi.network

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CleartextGuardInterceptor
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NetworkHelper(
    private val context: Context,
    private val sourceIDProvider: (() -> String?)? = null,
    private val rootNetwork: NetworkHelper? = null,
) {
    private val owner: NetworkHelper get() = rootNetwork ?: this

    // Dispatcher and connection pool are process-scoped resources. Source
    // isolation belongs to cookies and response caches; sockets and scheduling
    // can safely be shared without allowing cookie leakage.
    private val sharedDispatcher: Dispatcher by lazy {
        if (rootNetwork == null) {
            Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            }
        } else {
            owner.sharedDispatcher
        }
    }
    private val sharedConnectionPool: ConnectionPool by lazy {
        if (rootNetwork == null) ConnectionPool() else owner.sharedConnectionPool
    }
    //    private val preferences: PreferencesHelper by injectLazy()

//    private val cacheDir = File(context.cacheDir, "network_cache")

//    private val cacheSize = 5L * 1024 * 1024 // 5 MiB

    // Tachidesk -->
    private val legacyCookieStore by lazy { PersistentCookieStore(context, "legacy") }
    private val sourceCookieStores = ConcurrentHashMap<String, PersistentCookieStore>()
    internal data class BrowserClearance(
        val cookies: List<Cookie>,
        val userAgent: String,
        val revision: String = UUID.randomUUID().toString(),
    )
    internal data class BrowserOverlaySelection(val key: String, val snapshot: BrowserClearance, val header: String)
    private val browserClearances = ConcurrentHashMap<String, BrowserClearance>()

    /** Legacy/global bucket. Identified source clients never use this bucket. */
    val cookieStore: PersistentCookieStore
        get() {
            val provider = sourceIDProvider
            if (provider != null) {
                val sourceID = provider()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("source network request has no sourceId")
                return owner.sourceCookieStore(sourceID)
            }
            return owner.legacyCookieStore
        }

    /** Wire identity is opaque; preserve it exactly and hash only the prefs namespace. */
    fun sourceIDOrNull(): String? = sourceIDProvider?.invoke()?.also {
        require(it.isNotBlank()) { "source network request has no sourceId" }
    }

    fun sourceCookieStore(sourceID: String): PersistentCookieStore {
        require(sourceID.isNotBlank()) { "source network request has no sourceId" }
        val namespace = sourceID.toByteArray(Charsets.UTF_8).let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        return owner.sourceCookieStores.computeIfAbsent(sourceID) {
            PersistentCookieStore(owner.context, "source_$namespace")
        }
    }

    /** Browser-owned clearance overlay; never merged into extension cookies. */
    fun replaceBrowserClearance(host: String, cookies: List<Cookie>, userAgent: String) {
        require(userAgent.isNotBlank()) { "browser clearance requires a User-Agent" }
        val sourceID = sourceIDOrNull() ?: return
        owner.browserClearances["$sourceID\u0000${host.lowercase()}"] = BrowserClearance(cookies, userAgent)
    }

    fun clearBrowserClearance(host: String) {
        val sourceID = sourceIDOrNull() ?: return
        owner.browserClearances.remove("$sourceID\u0000${host.lowercase()}")
    }

    internal fun browserCookieSelection(url: HttpUrl, userAgent: String?): BrowserOverlaySelection? {
        val sourceID = sourceIDOrNull() ?: return null
        val prefix = "$sourceID\u0000"
        val candidate = owner.browserClearances.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) && it.value.userAgent == userAgent }
            .mapNotNull { entry ->
                val cookies = entry.value.cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                if (cookies.isEmpty()) null else entry to cookies
            }
            .sortedWith(compareByDescending<Pair<MutableMap.MutableEntry<String, BrowserClearance>, List<Cookie>>> { pair -> pair.second.maxOf { it.domain.length } })
            .firstOrNull() ?: return null
        val cookies = candidate.second.distinctBy { "${it.name}\u0000${it.domain}\u0000${it.path}" }
        return BrowserOverlaySelection(candidate.first.key, candidate.first.value, cookies.joinToString("; ") { "${it.name}=${it.value}" })
    }

    internal fun mergeBrowserClearance(selection: BrowserOverlaySelection, cookies: List<Cookie>, userAgent: String) {
        if (selection.snapshot.userAgent != userAgent) return
        val merged = BrowserClearance(
            cookies = selection.snapshot.cookies.filterNot { old -> cookies.any { it.name == old.name } } + cookies,
            userAgent = selection.snapshot.userAgent,
        )
        owner.browserClearances.replace(selection.key, selection.snapshot, merged)
    }

    /** Return an ABI-compatible NetworkHelper view bound to one source instance. */
    fun forSource(sourceID: () -> String?): NetworkHelper =
        NetworkHelper(owner.context, sourceID, owner)

    init {
        if (rootNetwork == null) {
            CookieHandler.setDefault(
                CookieManager(legacyCookieStore, CookiePolicy.ACCEPT_ALL),
            )
        }
    }
    // Tachidesk <--

    // GoMangaStableDefaultUA: the stock Suwayomi default is a DESKTOP Chrome UA.
    // The host pushes a STABLE ecosystem UA (the Tachiyomi/Mihon-family default
    // the extensions are tested against) over it at boot via setDefaultUserAgent.
    // Deliberately NOT the device WebKit UA: UA-sensitive source APIs degrade
    // under a mobile Safari UA (happymh page-list), and Cloudflare clearance/UA
    // pairing is per CF-walled request (CloudflareInterceptor), never global.
    private val userAgent = if (rootNetwork == null) MutableStateFlow(DEFAULT_USER_AGENT) else null
    val userAgentFlow get() = owner.userAgent!!.asStateFlow()

    fun defaultUserAgentProvider(): String = owner.userAgent!!.value

    // GoMangaStableDefaultUA: let the host push ONE canonical source UA at boot
    // (POST /api/v1/gomanga/default-ua). This is the only writer of the global
    // default — the CloudflareInterceptor's global adoption is disabled (see the
    // interceptor wiring below) and the cookie-seed route no longer adopts the
    // clearance UA, so a CF verify can never flip every source onto a mobile UA.
    //
    // Blank + equality guarded: repeated boot/relaunch seeds are free. Existing
    // source instances observe the new baseline through HttpSource.headers; no
    // source registry rebuild is needed.
    fun setDefaultUserAgent(ua: String) {
        if (rootNetwork == null) {
            if (ua.isNotBlank() && userAgent!!.value != ua) userAgent!!.value = ua
        } else {
            owner.setDefaultUserAgent(ua)
        }
    }

    // GoMangaInsecureHttpGate: mirror of the app-side InsecureSourcePolicy. The
    // host pushes the user's opt-in at boot AND on every toggle flip (POST
    // /api/v1/gomanga/allow-insecure-http). Fail-closed: false until the host
    // pushes the real value, so a source fetch racing the boot window is blocked
    // rather than leaking cleartext. Read LIVE per request by
    // CleartextGuardInterceptor { allowInsecureHttp.value }, so a toggle takes
    // effect with NO source rebuild — deliberately no drop(1) watcher (unlike
    // the UA flow, which must reset cached source headers).
    private val allowInsecureHttp = if (rootNetwork == null) MutableStateFlow(false) else null

    fun setAllowInsecureHttp(allowed: Boolean) {
        if (rootNetwork == null) allowInsecureHttp!!.value = allowed else owner.setAllowInsecureHttp(allowed)
    }

    private val baseClientBuilder: OkHttpClient.Builder
        get() {
            // GoMangaInsecureHttpGate: ONE instance, registered FIRST in BOTH
            // the application and network interceptor lists below. Reads the
            // live policy flow per request (no rebuild on toggle).
            val cleartextGuard = CleartextGuardInterceptor { owner.allowInsecureHttp!!.value }
            val builder =
                OkHttpClient
                    .Builder()
                    // GoMangaOkHttpConcurrency: bound concurrent source requests
                    // through the embedded JVM proxy. OkHttp's default is 64/5; a
                    // prior pass raised this to 128/16 to fill cover grids faster,
                    // but on the pure-interpreter DEVICE VM that let too many
                    // full-image heap buffers run at once, degrading the whole JVM
                    // over a session (local listSources 50ms->4.5s on iPhone 14
                    // Pro 2026-06-04; reproduced x1.8 on a forced-interpreter sim).
                    // Cap at 32/8 — aligned with the app's cover request limiter
                    // (8) so real parallelism is preserved while heap/GC pressure
                    // is bounded.
                    .dispatcher(owner.sharedDispatcher)
                    .connectionPool(owner.sharedConnectionPool)
                    .cookieJar(
                        if (sourceIDProvider == null) PersistentCookieJar(owner.legacyCookieStore)
                        else SourcePartitionedCookieJar(sourceIDProvider, owner::sourceCookieStore)
                    )
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(2, TimeUnit.MINUTES)
                    .cache(
                        Cache(
                            directory = Files.createTempDirectory("tachidesk_network_cache").toFile(),
                            maxSize = 5L * 1024 * 1024, // 5 MiB
                        ),
                    )
                    // GoMangaInsecureHttpGate FIRST in the app chain: the
                    // original-url check precedes UA/Cloudflare/RetryAndFollowUp/
                    // Connect, so a blocked cleartext request short-circuits to
                    // 496 before any socket opens.
                    .addInterceptor(cleartextGuard)
                    .addInterceptor(UncaughtExceptionInterceptor())
                    .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
                    // GoMangaInsecureHttpGate FIRST in the network chain too, so a
                    // redirect-downgraded (https->http) hop is refused before the
                    // logging network interceptor touches it. (v2.3 dropped the
                    // stock IgnoreGzip/Brotli network interceptors, so the guard is
                    // now the first — and only preceding — network interceptor.)
                    .addNetworkInterceptor(cleartextGuard)

            // if (preferences.verboseLogging().get()) {
            val httpLoggingInterceptor =
                HttpLoggingInterceptor(
                    object : HttpLoggingInterceptor.Logger {
                        val logger = KotlinLogging.logger { }

                        override fun log(message: String) {
                            logger.debug { message }
                        }
                    },
                ).apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
            // GoMangaSourceUserAgent: fill only when the extension did not provide a UA
            // Run after extension interceptors and on redirects, preserving explicit headers.
            builder.addNetworkInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            // }

            // GoMangaIOSContentEncoding: current extension libraries may append
            // OkHttp's optional zstd CompressionInterceptor to this shared client.
            // Its zstd-kmp JNI payload is built for desktop/Android and cannot be
            // loaded by an iOS process. Network interceptors run after extension-
            // supplied application interceptors, so replace only an advertised
            // zstd encoding at the final request boundary. Requests without zstd
            // keep OkHttp's normal transparent gzip path.
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val overlaySelection = browserCookieSelection(request.url, request.header("User-Agent"))
                val overlay = overlaySelection?.header
                val overlayUsed = overlaySelection != null
                val withOverlay = if (!overlayUsed) request else {
                    val retained = request.header("Cookie").orEmpty().split(';')
                        .map(String::trim).filter { it.isNotEmpty() && !it.substringBefore('=').equals("cf_clearance", true) }
                    request.newBuilder().header("Cookie", (retained + overlay).joinToString("; ")).build()
                }
                val response = chain.proceed(withOverlay)
                val requestUA = withOverlay.header("User-Agent")
                if (requestUA != null) {
                    val rotated = Cookie.parseAll(withOverlay.url, response.headers)
                        .filter { it.name.equals("cf_clearance", ignoreCase = true) }
                    if (rotated.isNotEmpty() && overlaySelection != null) {
                        mergeBrowserClearance(overlaySelection, rotated, requestUA)
                    }
                }
                if (!overlayUsed) response else response.newBuilder()
                    .removeHeader("Set-Cookie")
                    .apply {
                        response.headers.values("Set-Cookie")
                            .filterNot { it.substringBefore('=').trim().equals("cf_clearance", ignoreCase = true) }
                            .forEach { addHeader("Set-Cookie", it) }
                    }
                    .build()
            }

            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val advertisesZstd =
                    request.header("Accept-Encoding")
                        ?.split(',')
                        ?.any { it.trim().equals("zstd", ignoreCase = true) }
                        ?: false
                val compatibleRequest =
                    if (advertisesZstd) {
                        request.newBuilder().header("Accept-Encoding", "identity").build()
                    } else {
                        request
                    }
                chain.proceed(compatibleRequest)
            }

            // GoMangaStableDefaultUA: a CF solve must NOT adopt the clearance UA
            // as the server-wide default. The clearance UA is the host device's
            // WebKit UA, and UA-sensitive source APIs degrade under it for every
            // unrelated request (happymh page-list). The CloudflareInterceptor
            // already forces the clearance UA + cookies onto each CF-walled
            // request itself; the global default stays whatever the host pushed
            // via /api/v1/gomanga/default-ua.
            builder.addInterceptor(
                CloudflareInterceptor(setUserAgent = { _ -> }, sourceID = sourceIDProvider),
            )

            // when (preferences.dohProvider().get()) {
            //     PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            //     PREF_DOH_GOOGLE -> builder.dohGoogle()
            //     PREF_DOH_ADGUARD -> builder.dohAdGuard()
            //     PREF_DOH_QUAD9 -> builder.dohQuad9()
            //     PREF_DOH_ALIDNS -> builder.dohAliDNS()
            //     PREF_DOH_DNSPOD -> builder.dohDNSPod()
            //     PREF_DOH_360 -> builder.doh360()
            //     PREF_DOH_QUAD101 -> builder.dohQuad101()
            //     PREF_DOH_MULLVAD -> builder.dohMullvad()
            //     PREF_DOH_CONTROLD -> builder.dohControlD()
            //     PREF_DOH_NJALLA -> builder.dohNajalla()
            //     PREF_DOH_SHECAN -> builder.dohShecan()
            // }

            return builder
        }

//    val client by lazy { baseClientBuilder.cache(Cache(cacheDir, cacheSize)).build() }
    val client by lazy { baseClientBuilder.build() }

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient by lazy { client }

    companion object {
        // GoMangaStableDefaultUA: the stock desktop-Chrome default, used until
        // the host pushes the ecosystem UA at boot. Still a content-safe UA for
        // UA-sensitive sources if that push never arrives.
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
