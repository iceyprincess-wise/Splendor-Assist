package com.assistant.overlay.interceptor

enum class GoalkeeperState {
    IDLE,
    TRACKING,
    THREAT,
    SAVE,
    PANIC,
    CROSS,
    RECOVERY
}

object GoalkeeperStateMachine {

    @Volatile
    private var current =
        GoalkeeperState.IDLE

    // Q65-6 (batch 5b): transition()/state()/reset() are @Synchronized on
    // this object's monitor — the ONE lock shared with
    // RecoveryPositionEngine.begin/finish/recovering. A torn flag+state
    // pair is impossible: the flag write and the transition commit
    // atomically, and no state() read can land between them.
    @Synchronized
    fun state(): GoalkeeperState =
        current

    @Synchronized
    fun transition(
        next: GoalkeeperState
    ) {
        current = next
    }

    // No66-D3: session-reset hook — return the keeper to IDLE. Called
    // from GoalkeeperMetricsRegistry.reset() (via
    // RuntimeCoordinator.resetRuntimeState on session reset); the state
    // otherwise survived a reset and read stale in the snapshot until
    // the next threat evaluation. Q65-6 (batch 5b) is fixed separately:
    // this reset is @Synchronized on the shared monitor, so it cannot
    // land between RecoveryPositionEngine's flag write and its transition.
    @Synchronized
    fun reset() {
        current = GoalkeeperState.IDLE
    }
}
