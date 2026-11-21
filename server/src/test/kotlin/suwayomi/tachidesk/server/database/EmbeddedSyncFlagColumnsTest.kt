package suwayomi.tachidesk.server.database

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EmbeddedSyncFlagColumnsTest {
    @Test
    fun resumesPartialDdlAndKeepsExistingWriterValues() {
        DriverManager.getConnection("jdbc:h2:mem:flags-${UUID.randomUUID()}").use { connection ->
            connection.createStatement().use { sql ->
                sql.executeUpdate("CREATE TABLE CATEGORY (ID INT PRIMARY KEY, FLAGS INT NOT NULL DEFAULT 0)")
                sql.executeUpdate("CREATE TABLE MANGA (ID INT PRIMARY KEY, VIEWER_FLAGS INT NULL)")
                sql.executeUpdate("INSERT INTO CATEGORY (ID, FLAGS) VALUES (1, 17)")
                sql.executeUpdate("INSERT INTO MANGA (ID, VIEWER_FLAGS) VALUES (1, NULL), (2, 19)")
                assertFailsWith<IllegalStateException> {
                    EmbeddedSyncFlagColumns.ensure(connection, requireComplete = true)
                }
                EmbeddedSyncFlagColumns.ensure(connection)
                sql.executeUpdate("UPDATE MANGA SET CHAPTER_FLAGS=23 WHERE ID=1")
                EmbeddedSyncFlagColumns.ensure(connection)
                sql.executeQuery("SELECT FLAGS FROM CATEGORY").use { rows ->
                    check(rows.next())
                    assertEquals(17, rows.getInt(1))
                }
                sql.executeQuery("SELECT VIEWER, VIEWER_FLAGS, CHAPTER_FLAGS FROM MANGA ORDER BY ID").use { rows ->
                    check(rows.next())
                    assertEquals(0, rows.getInt(1))
                    assertNull(rows.getObject(2))
                    assertEquals(23, rows.getInt(3))
                    check(rows.next())
                    assertEquals(19, rows.getInt(2))
                }
            }
        }
    }

    @Test
    fun validatesAllExistingColumnsBeforeAddingAnything() {
        DriverManager.getConnection("jdbc:h2:mem:flags-invalid-${UUID.randomUUID()}").use { connection ->
            connection.createStatement().use { sql ->
                sql.executeUpdate("CREATE TABLE CATEGORY (ID INT PRIMARY KEY)")
                sql.executeUpdate("CREATE TABLE MANGA (ID INT PRIMARY KEY, CHAPTER_FLAGS VARCHAR(32))")
                sql.executeUpdate("INSERT INTO MANGA (ID, CHAPTER_FLAGS) VALUES (1, 'retain')")
                assertFailsWith<IllegalStateException> { EmbeddedSyncFlagColumns.ensure(connection) }
                assertFailsWith<java.sql.SQLException> { sql.executeQuery("SELECT FLAGS FROM CATEGORY").close() }
                sql.executeQuery("SELECT CHAPTER_FLAGS FROM MANGA").use { rows ->
                    check(rows.next())
                    assertEquals("retain", rows.getString(1))
                }
            }
        }
    }
}
