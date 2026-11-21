package suwayomi.tachidesk.server.database

/** Synchronous callbacks from the primordially started embedded JVM. */
internal object EmbeddedDatabaseUpgradeProgress {
    fun convertingFormat() = report(1)

    fun migratingSchema() = report(2)

    private fun report(phase: Int) {
        try {
            reportPhase(phase)
        } catch (_: UnsatisfiedLinkError) {
            // Standalone JVM and desktop fixtures have no Swift progress consumer.
        }
    }

    @JvmStatic
    private external fun reportPhase(phase: Int)
}
