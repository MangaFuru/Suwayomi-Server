package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Route
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GoMangaInsecureHttpGate contract. No network / MockWebServer — a hand-rolled
 * fake Chain exercises the interceptor's branching directly:
 *  - application invocation (connection == null): a blocked cleartext request
 *    short-circuits to 496 WITHOUT calling proceed (no socket).
 *  - network invocation (connection != null): a blocked hop THROWS.
 *  - https / loopback / policy-ON: pure passthrough (proceed called once).
 *
 * Run manually: ./gradlew :server:test --tests "*CleartextGuardInterceptor*"
 */
class CleartextGuardInterceptorTest {

    private fun req(url: String) = Request.Builder().url(url).build()

    // A fake Chain that records whether proceed() ran and returns a canned 200.
    private class FakeChain(
        private val request: Request,
        private val connected: Boolean,
    ) : Interceptor.Chain {
        private val client = OkHttpClient()

        var proceedCalled = false
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceedCalled = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = if (connected) StubConnection else null

        override fun call(): Call = throw NotImplementedError()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override val followSslRedirects: Boolean get() = client.followSslRedirects
        override val followRedirects: Boolean get() = client.followRedirects
        override val dns: Dns get() = client.dns
        override val socketFactory: SocketFactory get() = client.socketFactory
        override val retryOnConnectionFailure: Boolean get() = client.retryOnConnectionFailure
        override val authenticator: Authenticator get() = client.authenticator
        override val cookieJar: CookieJar get() = client.cookieJar
        override val cache: Cache? get() = client.cache
        override val proxy: Proxy? get() = client.proxy
        override val proxySelector: ProxySelector get() = client.proxySelector
        override val proxyAuthenticator: Authenticator get() = client.proxyAuthenticator
        override val sslSocketFactoryOrNull: SSLSocketFactory? get() = client.sslSocketFactory
        override val x509TrustManagerOrNull: X509TrustManager? get() = client.x509TrustManager
        override val hostnameVerifier: HostnameVerifier get() = client.hostnameVerifier
        override val certificatePinner: CertificatePinner get() = client.certificatePinner
        override val connectionPool: ConnectionPool get() = client.connectionPool
        override val eventListener: EventListener get() = EventListener.NONE

        override fun withDns(dns: Dns): Interceptor.Chain = this
        override fun withSocketFactory(socketFactory: SocketFactory): Interceptor.Chain = this
        override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean): Interceptor.Chain = this
        override fun withAuthenticator(authenticator: Authenticator): Interceptor.Chain = this
        override fun withCookieJar(cookieJar: CookieJar): Interceptor.Chain = this
        override fun withCache(cache: Cache?): Interceptor.Chain = this
        override fun withProxy(proxy: Proxy?): Interceptor.Chain = this
        override fun withProxySelector(proxySelector: ProxySelector): Interceptor.Chain = this
        override fun withProxyAuthenticator(proxyAuthenticator: Authenticator): Interceptor.Chain = this

        override fun withSslSocketFactory(
            sslSocketFactory: SSLSocketFactory?,
            x509TrustManager: X509TrustManager?,
        ): Interceptor.Chain = this

        override fun withHostnameVerifier(hostnameVerifier: HostnameVerifier): Interceptor.Chain = this
        override fun withCertificatePinner(certificatePinner: CertificatePinner): Interceptor.Chain = this
        override fun withConnectionPool(connectionPool: ConnectionPool): Interceptor.Chain = this
    }

    private object StubConnection : Connection {
        override fun route(): Route = throw NotImplementedError()
        override fun socket(): Socket = throw NotImplementedError()
        override fun handshake(): Handshake? = null
        override fun protocol(): Protocol = Protocol.HTTP_1_1
    }

    private fun gate(allow: Boolean) = CleartextGuardInterceptor { allow }

    @Test
    fun `policy off - application-path cleartext http short-circuits to 496 without connecting`() {
        val chain = FakeChain(req("http://cdn.example.com/p.jpg"), connected = false)
        val response = gate(allow = false).intercept(chain)
        assertEquals(CleartextGuardInterceptor.INSECURE_BLOCKED_CODE, response.code)
        assertEquals("1", response.header("X-GoManga-Insecure-Blocked"))
        assertFalse(chain.proceedCalled, "must not dial out for a blocked request")
    }

    @Test
    fun `policy off - network-path cleartext http hop throws`() {
        val chain = FakeChain(req("http://cdn.example.com/p.jpg"), connected = true)
        assertFailsWith<IOException> { gate(allow = false).intercept(chain) }
        assertFalse(chain.proceedCalled, "the downgraded hop is refused")
    }

    @Test
    fun `policy off - https is always allowed (both invocations)`() {
        for (connected in listOf(false, true)) {
            val chain = FakeChain(req("https://cdn.example.com/p.jpg"), connected)
            val response = gate(allow = false).intercept(chain)
            assertEquals(200, response.code)
            assertTrue(chain.proceedCalled)
        }
    }

    @Test
    fun `policy off - loopback cleartext is exempt (embedded server, CF proxy)`() {
        for (host in listOf("127.0.0.1", "localhost")) {
            val chain = FakeChain(req("http://$host:4571/api/v1/x"), connected = false)
            val response = gate(allow = false).intercept(chain)
            assertEquals(200, response.code)
            assertTrue(chain.proceedCalled)
        }
    }

    @Test
    fun `policy on - cleartext http is a pure no-op (both invocations)`() {
        for (connected in listOf(false, true)) {
            val chain = FakeChain(req("http://cdn.example.com/p.jpg"), connected)
            val response = gate(allow = true).intercept(chain)
            assertEquals(200, response.code)
            assertTrue(chain.proceedCalled, "ON must proceed unconditionally — no synthetic response, no throw")
        }
    }
}
