package suwayomi.tachidesk.server.database

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddedMigrationLedgerTest {
    @Test
    fun futureReceiptDoesNotSkipPendingKnownWork() {
        withLedger("VERSION INT PRIMARY KEY") { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO MIGRATIONS VALUES (1, 'Initial', CURRENT_TIMESTAMP), (8, 'Future', CURRENT_TIMESTAMP)")
            }
            assertEquals(1, EmbeddedMigrationLedger.completedPrefix(connection, listOf("Initial", "Next")))
        }
    }

    @Test
    fun knownReceiptOutOfSequenceIsRejected() {
        withLedger("VERSION INT PRIMARY KEY") { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO MIGRATIONS VALUES (2, 'Next', CURRENT_TIMESTAMP)")
            }
            assertFailsWith<IllegalStateException> {
                EmbeddedMigrationLedger.completedPrefix(connection, listOf("Initial", "Next"))
            }
        }
    }

    @Test
    fun wrongLedgerColumnTypeIsRejectedWithoutCoercion() {
        withLedger("VERSION VARCHAR(40) PRIMARY KEY") { connection ->
            assertFailsWith<IllegalStateException> {
                EmbeddedMigrationLedger.completedPrefix(connection, listOf("Initial", "Next"))
            }
        }
    }

    private fun withLedger(
        versionColumn: String,
        exercise: (java.sql.Connection) -> Unit,
    ) {
        DriverManager.getConnection("jdbc:h2:mem:ledger-${UUID.randomUUID()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE MIGRATIONS($versionColumn, NAME VARCHAR(400), EXECUTED_AT TIMESTAMP(9))")
            }
            exercise(connection)
        }
    }
}
