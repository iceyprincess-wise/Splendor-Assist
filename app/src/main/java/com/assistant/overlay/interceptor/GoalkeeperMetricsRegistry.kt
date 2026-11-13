package com.assistant.overlay.interceptor

import java.util.concurrent.atomic.AtomicLong

object GoalkeeperMetricsRegistry {

    val triggerCount =
        AtomicLong()

    val saveAttempts =
        AtomicLong()

    val interceptions =
        AtomicLong()

    val panicSaves =
        AtomicLong()

    val recoveryCount =
        AtomicLong()

    val crossClaims =
        AtomicLong()

    fun snapshot(): String {

        return buildString {

            append("GK Trigger=")
            append(triggerCount.get())

            append(" Save=")
            append(saveAttempts.get())

            append(" Intercept=")
            append(interceptions.get())

            append(" Panic=")
            append(panicSaves.get())

            append(" Recovery=")
            append(recoveryCount.get())

            // SPLENDOR_P9_KEEPER_STATE: GoalkeeperStateMachine.state() had
            // zero readers -- keeper state was tracked but never consumed.
            // Surface it here so the live state is observable in logs.
            append(" State=")
            append(GoalkeeperStateMachine.state().name)
        }
    }
}
