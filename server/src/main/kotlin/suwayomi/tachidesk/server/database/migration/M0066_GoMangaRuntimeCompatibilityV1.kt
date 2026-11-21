package suwayomi.tachidesk.server.database.migration

import de.neonew.exposed.migrations.Migration
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import suwayomi.tachidesk.server.database.EmbeddedSyncTriggers
import java.sql.Connection

@Suppress("ClassName", "unused")
class M0066_GoMangaRuntimeCompatibilityV1 : Migration() {
    override fun run() {
        val db = TransactionManager.current().connection.connection as Connection
        if (db.metaData.databaseProductName == "H2") EmbeddedSyncTriggers.reconcile(db)
    }
}
