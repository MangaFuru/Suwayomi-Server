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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NetworkHelper(
    context: Context,
) {
    //    private val preferences: PreferencesHelper by injectLazy()

//    private val cacheDir = File(context.cacheDir, "network_cache")

//    private val cacheSize = 5L * 1024 * 1024 // 5 MiB

    // Tachidesk -->
    val cookieStore = PersistentCookieStore(context)

    init {
        CookieHandler.setDefault(
            CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL),
        )
    }
    // Tachidesk <--

    // GoMangaStableDefaultUA: the stock Suwayomi default is a DESKTOP Chrome UA.
    // The host pushes a STABLE ecosystem UA (the Tachiyomi/Mihon-family default
    // the extensions are tested against) over it at boot via setDefaultUserAgent.
    // Deliberately NOT the device WebKit UA: UA-sensitive source APIs degrade
    // under a mobile Safari UA (happymh page-list), and Cloudflare clearance/UA
    // pairing is per CF-walled request (CloudflareInterceptor), never global.
    private val userAgent = MutableStateFlow(DEFAULT_USER_AGENT)
    val userAgentFlow = userAgent.asStateFlow()

    fun defaultUserAgentProvider(): String = userAgent.value

    // GoMangaStableDefaultUA: let the host push ONE canonical source UA at boot
    // (POST /api/v1/gomanga/default-ua). This is the only writer of the global
    // default — the CloudflareInterceptor's global adoption is disabled (see the
    // interceptor wiring below) and the cookie-seed route no longer adopts the
    // clearance UA, so a CF verify can never flip every source onto a mobile UA.
    //
    // Blank + equality guarded: a UA change fires the drop(1) watcher below
    // (unregisterAllCatalogueSources, rebuilding every cached source). Re-pushing
    // the same UA (every boot/relaunch seeds it) must be a no-op or it churns
    // live source instances for nothing. Set once at boot on a cold cache; the
    // guard makes repeats free.
    fun setDefaultUserAgent(ua: String) {
        if (ua.isNotBlank() && ua != userAgent.value) {
            userAgent.value = ua
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
    private val allowInsecureHttp = MutableStateFlow(false)

    fun setAllowInsecureHttp(allowed: Boolean) {
        allowInsecureHttp.value = allowed
    }

    init {
        @OptIn(DelicateCoroutinesApi::class)
        userAgent
            .drop(1)
            .onEach {
                GetSource.unregisterAllSources() // need to reset the headers
            }.launchIn(GlobalScope)
    }

    private val baseClientBuilder: OkHttpClient.Builder
        get() {
            // GoMangaInsecureHttpGate: ONE instance, registered FIRST in BOTH
            // the application and network interceptor lists below. Reads the
            // live policy flow per request (no rebuild on toggle).
            val cleartextGuard = CleartextGuardInterceptor { allowInsecureHttp.value }
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
                    .dispatcher(
                        okhttp3.Dispatcher().apply {
                            maxRequests = 32
                            maxRequestsPerHost = 8
                        },
                    )
                    .cookieJar(PersistentCookieJar(cookieStore))
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
            // }

            // GoMangaStableDefaultUA: a CF solve must NOT adopt the clearance UA
            // as the server-wide default. The clearance UA is the host device's
            // WebKit UA, and UA-sensitive source APIs degrade under it for every
            // unrelated request (happymh page-list). The CloudflareInterceptor
            // already forces the clearance UA + cookies onto each CF-walled
            // request itself; the global default stays whatever the host pushed
            // via /api/v1/gomanga/default-ua.
            builder.addInterceptor(
                CloudflareInterceptor(setUserAgent = { _ -> }),
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
