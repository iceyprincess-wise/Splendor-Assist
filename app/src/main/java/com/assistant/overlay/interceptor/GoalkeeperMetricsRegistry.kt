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

    val reactionSamples =
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
        }
    }
}
