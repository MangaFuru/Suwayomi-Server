package suwayomi.tachidesk.server.database

import org.h2.Driver
import java.io.OutputStream
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.UUID

/** Stages the engine conversion and schema migration before replacing a legacy file. */
internal object EmbeddedLegacyH2 {
    private const val LEGACY_SHA256 =
        "3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6"

    fun convert(
        original: Path,
        bundledLegacyDriver: Path,
        requiredFreeBytes: Long,
        normalizeLegacyReceiptTimestamp: Boolean?,
        knownNames: List<String>? = null,
        migrateStaged: (Path) -> Unit,
    ) {
        check(requiredFreeBytes > 0) { "Legacy conversion capacity budget is missing" }
        check(Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) { "Legacy database is unavailable" }
        check(original.fileName.toString() == "database.mv.db") { "Unexpected legacy database entry" }
        check(Files.getFileStore(original.parent).usableSpace >= requiredFreeBytes) {
            "Insufficient free space for embedded database conversion"
        }
        check(digest(bundledLegacyDriver) == LEGACY_SHA256) { "Bundled legacy H2 driver identity mismatch" }
        check(';' !in original.toAbsolutePath().toString()) { "Unsupported legacy database path" }
        val before = digest(original)
        val workspace = original.parent.resolve(".gomanga-h2-" + UUID.randomUUID())
        Files.createDirectory(workspace)
        try {
            // Only the converter writes this marker. Name, original digest, and
            // the closed set of owned files also fence later cleanup after a crash.
            Files.writeString(workspace.resolve("owner"), "gomanga-h2-v1\n${workspace.fileName}\n$before\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
            val export = workspace.resolve("legacy.sql")
            val normalized = workspace.resolve("import.sql")
            val staged = workspace.resolve("database.mv.db")
            val properties = Properties()
            properties.setProperty("user", "")
            properties.setProperty("password", "")
            val legacyURL = url(original) + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0"
            val normalizeTimestamp = URLClassLoader(
                arrayOf(bundledLegacyDriver.toUri().toURL()),
                ClassLoader.getPlatformClassLoader(),
            ).use { loader ->
                val bridge = loader.loadClass("org.h2.Driver").getDeclaredConstructor().newInstance() as java.sql.Driver
                bridge.connect(legacyURL, properties).use { legacy ->
                    check(legacy.metaData.databaseProductVersion.startsWith("1.4.200")) {
                        "Unrecognized legacy database engine"
                    }
                    val needsNormalization = knownNames?.let {
                        when (EmbeddedMigrationLedger.completedPrefix(legacy, it)) {
                            38 -> true
                            54 -> false
                            else -> error("Unexpected legacy H2 migration receipts")
                        }
                    } ?: requireNotNull(normalizeLegacyReceiptTimestamp)
                    legacy.createStatement().use { it.execute("SCRIPT TO ${quoted(export)}") }
                    needsNormalization
                }
            }

            val input =
                if (normalizeTimestamp) {
                    normalizeReceiptTimestamp(export, normalized)
                    Files.delete(export)
                    normalized
                } else {
                    export
                }

            Driver().connect(url(staged), properties).use { target ->
                check(target.metaData.databaseProductVersion.startsWith("2.4.240")) {
                    "Unrecognized target database engine"
                }
                target.createStatement().use { it.execute("RUNSCRIPT FROM ${quoted(input)} FROM_1X") }
            }
            Files.delete(input)
            migrateStaged(staged)
            check(digest(original) == before) { "Legacy database changed during conversion" }
            FileChannel.open(staged, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(staged, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            removeOwnedWorkspace(workspace)
        }
    }

    /** Called after successful admission while the per-root converter lock is held. */
    fun removeAbandonedWorkspaces(original: Path) {
        Files.newDirectoryStream(original.parent, ".gomanga-h2-*").use { dirs ->
            for (dir in dirs) removeOwnedWorkspace(dir)
        }
    }

    private fun removeOwnedWorkspace(dir: Path) {
        val name = dir.fileName.toString()
        val id = name.removePrefix(".gomanga-h2-")
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id ||
            !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        val owner = dir.resolve("owner")
        if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) || Files.size(owner) > 180) return
        val lines = Files.readAllLines(owner)
        if (lines.size != 3 || lines[0] != "gomanga-h2-v1" || lines[1] != name ||
            !lines[2].matches(Regex("[0-9a-f]{64}"))) return
        val allowed = setOf("owner", "legacy.sql", "import.sql", "database.mv.db", "database.trace.db",
            "database.mv.db.tempFile", "database.mv.db.newFile")
        val contents = Files.newDirectoryStream(dir).use { it.toList() }
        if (contents.any { it.fileName.toString() !in allowed || !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) return
        for (file in contents) if (file != owner) Files.deleteIfExists(file)
        Files.deleteIfExists(owner)
        Files.deleteIfExists(dir)
    }

    private fun normalizeReceiptTimestamp(export: Path, normalized: Path) {
        val header = "CREATE CACHED TABLE \"PUBLIC\".\"MIGRATIONS\"("
        val oldDefinition = "\"EXECUTED_AT\" DATETIME(9) NOT NULL"
        var inside = false
        var changes = 0
        Files.newBufferedReader(export).use { source ->
            Files.newBufferedWriter(normalized).use { target ->
                source.lineSequence().forEach { line ->
                    if (line.startsWith(header)) inside = true
                    val adapted =
                        if (inside && line.trim() == oldDefinition) {
                            changes++
                            line.replace("DATETIME", "TIMESTAMP")
                        } else {
                            line
                        }
                    if (line.trimStart().startsWith(");")) inside = false
                    target.write(adapted)
                    target.newLine()
                }
            }
        }
        check(changes == 1) { "Unrecognized legacy receipt timestamp encoding" }
    }

    private fun url(file: Path): String = "jdbc:h2:${file.toAbsolutePath().toString().removeSuffix(".mv.db")}"

    private fun quoted(file: Path): String = "'${file.toAbsolutePath().toString().replace("'", "''")}'"

    private fun digest(file: Path): String {
        val sha = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file), sha).use { it.transferTo(OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(sha.digest())
    }
}
