package com.assistant.overlay.interceptor


object RecoveryPositionEngine {

    @Volatile
    private var recovering = false

    fun beginRecovery() {
        recovering = true
        GoalkeeperStateMachine.transition(
            GoalkeeperState.RECOVERY
        )
    }

    fun finishRecovery() {
        recovering = false
        GoalkeeperStateMachine.transition(
            GoalkeeperState.IDLE
        )
    }

    fun recovering(): Boolean {
        // Patch 4: a random coin-flip used to drop ~0.05% of true
        // recoveries. Recovery state is reported as-is.
        return recovering
    }
}
