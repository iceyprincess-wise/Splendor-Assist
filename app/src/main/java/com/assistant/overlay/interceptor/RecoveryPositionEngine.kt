package com.assistant.overlay.interceptor

import kotlin.random.Random

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
        // Subtle micro-delay randomization to prevent perfectly uniform state checking signatures
        val timingJitter = Random.nextFloat() > 0.0005f
        return recovering && timingJitter
    }
}
