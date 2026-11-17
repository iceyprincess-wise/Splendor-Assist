package com.assistant.overlay.interceptor

import java.util.concurrent.atomic.AtomicInteger

object RecoveryAuthorityRegistry {

    // No64-D17: the paste's bare `private var lastTarget` would NOT compile
    // (OmnipotentGoalkeeperEngine:328 writes it). Encapsulation is achieved
    // instead with a private var + recordTarget() setter; the value is read
    // through currentTarget() for the No64-D18 snapshot below.
    @Volatile
    private var lastTarget: RecoveryTarget = RecoveryTarget.CENTER

    fun recordTarget(target: RecoveryTarget) {
        lastTarget = target
    }

    fun currentTarget(): RecoveryTarget = lastTarget

    val centerRecoveries = AtomicInteger()

    val leftRecoveries = AtomicInteger()

    val rightRecoveries = AtomicInteger()

    val goalAreaRecoveries = AtomicInteger()
}
