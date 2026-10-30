object GameStateBuilder {

    @Volatile
    private var latest = GameStateSnapshot()

    fun update(
        snapshot: GameStateSnapshot
    ) {
        latest = snapshot
    }

    fun current(): GameStateSnapshot {
        return latest
    }
}
/* ======
GameStateBuilder Anchor
====== */

/* ========
GameStateFusion
======== */
