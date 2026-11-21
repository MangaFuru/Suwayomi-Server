package suwayomi.tachidesk.server.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

@EnabledIfEnvironmentVariable(named = "GOMANGA_VERIFICATION_FIXTURES", matches = ".+")
class HistoricalAdmissionTest : ApplicationTest() {
    @TempDir lateinit var root: Path

    @Test fun h38Admission() = verify("v0.5.34")
    @Test fun h22Admission() = verify("v1.0.34")
    @Test fun h23Admission() = verify("v1.1.21")

    @Test fun failedRecoveryDoesNotPromoteOrRemoveTheOriginal() {
        val source = Path.of(System.getenv("GOMANGA_VERIFICATION_FIXTURES"), "v1.1.21", "database.mv.db")
        val original = Files.copy(source, root.resolve("database.mv.db"))
        val failure = runCatching {
            EmbeddedLegacyH2.recoverCurrent(original) { staged ->
                check(staged != original && Files.mismatch(staged, original) == -1L)
                Files.writeString(staged, "incomplete recovered database")
                error("Rejected staged schema")
            }
        }.exceptionOrNull()
        check(failure?.message == "Rejected staged schema")
        check(Files.mismatch(original, source) == -1L)
        Files.list(root).use { files -> check(files.toList() == listOf(original)) }
    }

    @Test fun incompatibleFlagDoesNotChangeInput() {
        val source = Path.of(System.getenv("GOMANGA_VERIFICATION_FIXTURES"), "v1.1.21", "database.mv.db")
        val input = Files.copy(source, root.resolve("database.mv.db"))
        val url = "jdbc:h2:${root.resolve("database")};IFEXISTS=TRUE"
        DriverManager.getConnection(url, "", "").use { db ->
            db.createStatement().use { it.execute("ALTER TABLE CATEGORY ADD FLAGS VARCHAR") }
        }
        val before = Files.readAllBytes(input)
        val legacy = Path.of(System.getenv("GOMANGA_VERIFICATION_H2_DRIVER"))
        val failure = runCatching { EmbeddedDatabaseAdmission.prepare(ApplicationDirs(root.toString()), legacy) }.exceptionOrNull()
        check(failure is IllegalStateException && failure.message == "Incompatible persisted sync flag definition") { failure.toString() }
        check(Files.readAllBytes(input).contentEquals(before))
    }

    @Test fun membershipDependentSurvivesDeduplicationAndReopen() {
        val fixture = Path.of(System.getenv("GOMANGA_VERIFICATION_FIXTURES"), "v1.1.21", "duplicate-projections.mv.db")
        Files.copy(fixture, root.resolve("database.mv.db"))
        val url = "jdbc:h2:${root.resolve("database")};IFEXISTS=TRUE"
        DriverManager.getConnection(url, "", "").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE EXTRA_MEMBER_NOTE (MEMBER_ID INT REFERENCES CATEGORYMANGA(ID) ON DELETE CASCADE, NOTE VARCHAR)")
                sql.execute("INSERT INTO EXTRA_MEMBER_NOTE VALUES (913, 'preserve')")
            }
        }
        repeat(3) {
            EmbeddedDatabaseAdmission.prepare(ApplicationDirs(root.toString()), Path.of(System.getenv("GOMANGA_VERIFICATION_H2_DRIVER")))
            DriverManager.getConnection(url, "", "").use { db ->
                db.createStatement().use { sql ->
                    sql.executeQuery("SELECT M.ID, N.NOTE FROM EXTRA_MEMBER_NOTE N JOIN CATEGORYMANGA M ON M.ID=N.MEMBER_ID").use { row ->
                        check(row.next() && row.getInt(1) == 905 && row.getString(2) == "preserve" && !row.next())
                    }
                }
            }
        }
    }

    private fun verify(version: String) {
        val source = Path.of(System.getenv("GOMANGA_VERIFICATION_FIXTURES"), version, "database.mv.db")
        Files.copy(source, root.resolve("database.mv.db"))
        val legacy = Path.of(System.getenv("GOMANGA_VERIFICATION_H2_DRIVER"))
        repeat(2) {
            EmbeddedDatabaseAdmission.prepare(ApplicationDirs(root.toString()), legacy)
            DriverManager.getConnection("jdbc:h2:${root.resolve("database")};IFEXISTS=TRUE", "", "").use { db ->
                db.createStatement().use { sql ->
                    sql.executeQuery("SELECT LAST_PAGE_READ,BOOKMARK FROM CHAPTER WHERE ID=906").use { rows ->
                        check(rows.next() && rows.getInt(1)==12 && rows.getBoolean(2))
                    }
                    sql.executeQuery("SELECT COUNT(*) FROM MIGRATIONS WHERE VERSION BETWEEN 1 AND 66").use { rows ->
                        check(rows.next() && rows.getInt(1)==66)
                    }
                }
            }
        }
    }
}
