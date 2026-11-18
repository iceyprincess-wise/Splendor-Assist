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

    fun state(): GoalkeeperState =
        current

    fun transition(
        next: GoalkeeperState
    ) {
        current = next
    }

    // No66-D3: session-reset hook — return the keeper to IDLE. Called
    // from GoalkeeperMetricsRegistry.reset() (via
    // RuntimeCoordinator.resetRuntimeState on session reset); the state
    // otherwise survived a reset and read stale in the snapshot until
    // the next threat evaluation. This does NOT fix the Q65-6 torn read
    // (flag + state are still two separate volatile writes) — it only
    // clears the stale value on reset.
    fun reset() {
        current = GoalkeeperState.IDLE
    }
}
