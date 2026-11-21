package xyz.nulldev.androidcompat.io.sharedprefs

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Policy and ephemeral capture fingerprints; this contains no durable user store. */
object SourcePreferencePersistenceCapability {
    const val SYSTEM_PROPERTY = "gomanga.source_preferences.persistence"
    const val MEMORY_ONLY_VALUE = "memory-only-v1"

    fun memoryOnly(name: String): Boolean =
        name.startsWith("source_") && System.getProperty(SYSTEM_PROPERTY) == MEMORY_ONLY_VALUE

    fun fileFingerprint(file: Path): String? =
        if (Files.exists(file)) digest(Files.readAllBytes(file)) else null

    fun propertiesFingerprint(properties: Properties): String = synchronized(properties) {
        val digest = MessageDigest.getInstance("SHA-256")
        properties.stringPropertyNames().sorted().forEach { key ->
            for (value in listOf(key, properties.getProperty(key))) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
