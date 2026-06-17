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

    fun recovering(): Boolean =
        recovering
}
