package xyz.nulldev.androidcompat.io.sharedprefs

import android.content.SharedPreferences
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourcePreferencePersistenceTest {
    private lateinit var root: Path
    private var previousRoot: String? = null
    private var previousMode: String? = null
    private val rootProperty = "suwayomi.tachidesk.config.server.rootDir"

    @BeforeTest fun setup() {
        root = Files.createTempDirectory("gomanga-source-pref-test")
        previousRoot = System.getProperty(rootProperty)
        previousMode = System.getProperty(SourcePreferencePersistenceCapability.SYSTEM_PROPERTY)
        System.setProperty(rootProperty, root.toString())
        System.setProperty(SourcePreferencePersistenceCapability.SYSTEM_PROPERTY, SourcePreferencePersistenceCapability.MEMORY_ONLY_VALUE)
    }

    @AfterTest fun cleanup() {
        fun restore(key: String, value: String?) {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
        restore(rootProperty, previousRoot)
        restore(SourcePreferencePersistenceCapability.SYSTEM_PROPERTY, previousMode)
        root.toFile().deleteRecursively()
    }

    private fun file(name: String) = root.resolve("settings").resolve("$name.xml")
    private fun legacy(name: String): ByteArray {
        System.clearProperty(SourcePreferencePersistenceCapability.SYSTEM_PROPERTY)
        JavaSharedPreferences(name).edit().putBoolean("enabled", false)
            .putString("url", "https://custom.example")
            .putStringSet("languages", mutableSetOf("zh", "en")).commit()
        System.setProperty(SourcePreferencePersistenceCapability.SYSTEM_PROPERTY, SourcePreferencePersistenceCapability.MEMORY_ONLY_VALUE)
        return Files.readAllBytes(file(name))
    }

    @Test fun falseStringSetAndListenersStayInMemoryWithoutXML() {
        val name = "source_101"
        val prefs = JavaSharedPreferences(name)
        val changed = mutableListOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        assertTrue(prefs.edit().putBoolean("enabled", false)
            .putString("url", "https://custom.example")
            .putStringSet("languages", mutableSetOf("zh", "en")).commit())
        assertFalse(prefs.getBoolean("enabled", true))
        assertEquals("https://custom.example", prefs.getString("url", null))
        assertEquals(setOf("en", "zh"), prefs.getStringSet("languages", null))
        assertTrue(changed.containsAll(listOf("enabled", "url", "languages")))
        assertFalse(Files.exists(file(name)))
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        val count = changed.size
        prefs.edit().putBoolean("enabled", true).apply()
        assertEquals(count, changed.size)
    }

    @Test fun legacyValuesAreReadOnlyUntilSuccessfulCanonicalAcknowledgement() {
        val name = "source_102"
        val bytes = legacy(name)
        val prefs = JavaSharedPreferences(name)
        assertFalse(prefs.getBoolean("enabled", true))
        assertEquals("https://custom.example", prefs.getString("url", null))
        assertEquals(setOf("en", "zh"), prefs.getStringSet("languages", null))
        assertTrue(prefs.registerCanonicalCapture("captured"))
        assertFalse(prefs.acknowledgeCanonicalCapture("captured", committed = false))
        assertContentEquals(bytes, Files.readAllBytes(file(name)))
        assertTrue(prefs.acknowledgeCanonicalCapture("captured", committed = true))
        assertFalse(Files.exists(file(name)))
        assertFalse(prefs.acknowledgeCanonicalCapture("captured", committed = true))
    }

    @Test fun memoryMutationInvalidatesOriginalCaptureWithoutDeletingLegacy() {
        val name = "source_103"
        val bytes = legacy(name)
        val prefs = JavaSharedPreferences(name)
        assertTrue(prefs.registerCanonicalCapture("original"))
        prefs.edit().putBoolean("enabled", true).apply()
        assertFalse(prefs.acknowledgeCanonicalCapture("original", committed = true))
        assertContentEquals(bytes, Files.readAllBytes(file(name)))
        assertTrue(prefs.registerCanonicalCapture("newer"))
        assertFalse(prefs.acknowledgeCanonicalCapture("original", committed = true))
        assertTrue(prefs.acknowledgeCanonicalCapture("newer", committed = true))
        assertFalse(Files.exists(file(name)))
    }

    @Test fun replacedLegacyFileInvalidatesCaptureWithoutDeletingReplacement() {
        val name = "source_104"
        val original = legacy(name)
        val prefs = JavaSharedPreferences(name)
        assertTrue(prefs.registerCanonicalCapture("original"))
        val replacement = "replaced-input".toByteArray()
        Files.write(file(name), replacement)
        assertFalse(prefs.acknowledgeCanonicalCapture("original", committed = true))
        assertContentEquals(replacement, Files.readAllBytes(file(name)))
        Files.write(file(name), original)
        assertTrue(prefs.registerCanonicalCapture("replacement"))
        assertFalse(prefs.acknowledgeCanonicalCapture("original", committed = true))
        assertTrue(prefs.acknowledgeCanonicalCapture("replacement", committed = true))
    }

    @Test fun wrongTokenCannotRetireLegacyAndNonSourceStoreKeepsNativeBehavior() {
        val name = "source_105"
        legacy(name)
        val prefs = JavaSharedPreferences(name)
        assertTrue(prefs.registerCanonicalCapture("right"))
        assertFalse(prefs.acknowledgeCanonicalCapture("wrong", committed = true))
        assertTrue(Files.exists(file(name)))
        val native = JavaSharedPreferences("technical_control")
        assertFalse(native.isMemoryOnly())
        assertTrue(native.edit().putBoolean("enabled", false).commit())
        assertTrue(Files.exists(file("technical_control")))
        assertFalse(native.registerCanonicalCapture("not-source"))
        assertFalse(native.acknowledgeCanonicalCapture("not-source", committed = true))
    }
}
