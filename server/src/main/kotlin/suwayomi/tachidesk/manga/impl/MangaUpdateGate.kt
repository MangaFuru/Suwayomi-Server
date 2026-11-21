package suwayomi.tachidesk.manga.impl

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/** Deliberate bounded-wait result for a source update already in progress. */
class MangaUpdateGateTimeout(
    val sourceId: String,
    val mangaUrl: String,
    val timeoutMillis: Long,
) : RuntimeException(
    "timed out waiting for manga update gate after ${timeoutMillis}ms",
)

/**
 * One extension invocation per source/manga URL across every runtime route.
 *
 * The map owns entries while there is an active invocation or a waiter. A
 * disconnected HTTP request may cancel its wait, but it cannot unlock an
 * invocation owned by another coroutine; the explicit lock releases ownership
 * only when the extension call actually returns or throws.
 */
object MangaUpdateGate {
    private val entries = ConcurrentHashMap<Key, Entry>()
    private val registryLock = Any()
    // Match the host's existing source request deadline; the gate must not
    // turn a queued but valid refresh into an earlier failure.
    private val waitTimeout = 4.minutes

    private data class Key(val sourceId: String, val mangaUrl: String)

    private class Entry {
        val mutex = Mutex()
        val users = AtomicInteger(0)
    }

    /** Narrow fixture/audit observation; no cookies, URLs or source payloads. */
    fun users(sourceId: String, mangaUrl: String): Int {
        val key = Key(sourceId, mangaUrl)
        return synchronized(registryLock) { entries[key]?.users?.get() ?: 0 }
    }

    /** Fixture-only observation that a second caller is queued on any key. */
    fun hasWaiter(): Boolean = synchronized(registryLock) {
        entries.values.any { it.users.get() >= 2 }
    }

    suspend fun <T> withPermit(
        sourceId: String,
        mangaUrl: String,
        block: suspend () -> T,
    ): T {
        val key = Key(sourceId, mangaUrl)
        val entry = synchronized(registryLock) {
            entries[key]?.also { it.users.incrementAndGet() }
                ?: Entry().also {
                    it.users.incrementAndGet()
                    entries[key] = it
                }
        }
        var acquired = false
        try {
            val acquiredWithinDeadline = withTimeoutOrNull(waitTimeout) {
                entry.mutex.lock()
                acquired = true
                true
            } ?: false
            if (!acquiredWithinDeadline) {
                // withTimeoutOrNull returns null only for this wait's deadline;
                // caller cancellation still propagates as CancellationException.
                throw MangaUpdateGateTimeout(sourceId, mangaUrl, waitTimeout.inWholeMilliseconds)
            }
            return block()
        } finally {
            if (acquired) entry.mutex.unlock()
            synchronized(registryLock) {
                if (entry.users.decrementAndGet() == 0 && !entry.mutex.isLocked) {
                    entries.remove(key, entry)
                }
            }
        }
    }
}
