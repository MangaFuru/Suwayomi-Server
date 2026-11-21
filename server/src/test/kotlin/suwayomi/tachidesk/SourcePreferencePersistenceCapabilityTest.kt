package suwayomi.tachidesk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import suwayomi.tachidesk.manga.impl.Source
import xyz.nulldev.androidcompat.io.sharedprefs.SourcePreferencePersistenceCapability

class SourcePreferencePersistenceCapabilityTest {
    private fun withMode(mode: String?, body: suspend () -> Unit) = runBlocking {
        val key = SourcePreferencePersistenceCapability.SYSTEM_PROPERTY
        val previous = System.getProperty(key)
        try {
            if (mode == null) System.clearProperty(key) else System.setProperty(key, mode)
            body()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test fun sourceWithoutPreferenceScreenUsesTheKnownBackendPolicy() = withMode(SourcePreferencePersistenceCapability.MEMORY_ONLY_VALUE) {
        val capability = Source.sourcePreferencePersistenceCapabilities(Long.MIN_VALUE)
        assertTrue(capability.supportsMemoryOnlySourcePreferences)
        assertTrue(capability.requiresCanonicalCommit)
        assertFalse(capability.hasLegacySourcePreferences)
    }

    @Test fun legacyBackendCannotClaimCanonicalSupport() = withMode(null) {
        val capability = Source.sourcePreferencePersistenceCapabilities(Long.MIN_VALUE)
        assertFalse(capability.supportsMemoryOnlySourcePreferences)
        assertTrue(capability.requiresCanonicalCommit)
    }
}
