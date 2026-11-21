package suwayomi.tachidesk.server.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "GOMANGA_VERIFICATION_FIXTURES", matches = ".+")
class ConversionRecoveryTest : ApplicationTest() {
    @TempDir lateinit var root: Path

    @Test fun cleanupAfterInterruptedConversionPreservesForeignFiles() {
        val original = Files.copy(Path.of(System.getenv("GOMANGA_VERIFICATION_FIXTURES"), "v1.0.34", "database.mv.db"),
            root.resolve("database.mv.db"))
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)))
        fun workspace(): Path {
            val dir = Files.createDirectory(root.resolve(".gomanga-h2-" + UUID.randomUUID()))
            Files.writeString(dir.resolve("owner"), "gomanga-h2-v1\n${dir.fileName}\n$hash\n")
            Files.writeString(dir.resolve("legacy.sql"), "partial")
            return dir
        }
        val abandoned = workspace()
        val foreign = workspace()
        Files.writeString(foreign.resolve("foreign"), "keep")
        val legacy = Path.of(System.getenv("GOMANGA_VERIFICATION_H2_DRIVER"))
        repeat(2) {
            EmbeddedDatabaseAdmission.prepare(ApplicationDirs(root.toString()), legacy)
            check(!Files.exists(abandoned))
            check(Files.readString(foreign.resolve("foreign")) == "keep")
            check(Files.readString(foreign.resolve("legacy.sql")) == "partial")
        }
    }
}
