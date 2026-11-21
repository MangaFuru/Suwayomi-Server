package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

// from TachiWeb-Server
class PersistentCookieJar(
    private val store: PersistentCookieStore,
) : CookieJar {
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        store.addAll(url, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.get(url)
}


/**
 * CookieJar used by an identified extension source. The source identity is
 * resolved at request time so a subclass client initialized before the source's
 * lazy id is evaluated cannot fall back to the legacy global bucket.
 */
class SourcePartitionedCookieJar(
    private val sourceID: () -> String?,
    private val storeForSource: (String) -> PersistentCookieStore,
) : CookieJar {
    private fun store(): PersistentCookieStore =
        storeForSource(sourceID()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("source network request has no sourceId"))

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store().addAll(url, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store().get(url)
}
