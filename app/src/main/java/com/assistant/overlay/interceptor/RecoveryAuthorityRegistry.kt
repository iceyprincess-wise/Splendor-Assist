package com.assistant.overlay.interceptor

import java.util.concurrent.atomic.AtomicInteger

object RecoveryAuthorityRegistry {

    @Volatile
    var lastTarget: RecoveryTarget = RecoveryTarget.CENTER

    val centerRecoveries = AtomicInteger()

    val leftRecoveries = AtomicInteger()

    val rightRecoveries = AtomicInteger()

    val goalAreaRecoveries = AtomicInteger()
}
