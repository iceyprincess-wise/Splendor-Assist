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

    // No64-D23: zero every counter. Called from
    // RuntimeCoordinator.resetRuntimeState on session reset — the counters
    // previously had no reset and inflated the adaptive feedback bonuses
    // for the whole session (trace 2, B3). NOTE: the per-episode trackers
    // (GoalkeeperActionRouter.lastRoutedAction,
    // OmnipotentGoalkeeperEngine.lastRecoveryTarget /
    // lastClassifiedAction) are NOT reset here — they are self-correcting
    // (a stale value only suppresses one increment after a reset).
    fun reset() {
        triggerCount.set(0L)
        saveAttempts.set(0L)
        interceptions.set(0L)
        panicSaves.set(0L)
        recoveryCount.set(0L)
        crossClaims.set(0L)
    }

    fun snapshot(): String {

        return buildString {

            append("GK Trigger=")
            append(triggerCount.get())

            // No64-D28: this counts bus-accepted OFFERS, not dispatched
            // gestures — a cooling-dropped offer still counts (the
            // increment site is OmnipotentGoalkeeperEngine, on bus-accept).
            // Labelled as offers so the dashboard never reads as saves.
            append(" SaveOffers=")
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

            // No64-D18: RecoveryAuthorityRegistry.lastTarget + all four
            // counters were write-only (zero readers repo-wide). Surface
            // them here so the recovery telemetry is observable in logs.
            append(" RecoveryTarget=")
            append(RecoveryAuthorityRegistry.currentTarget().name)

            append(" CenterRec=")
            append(RecoveryAuthorityRegistry.centerRecoveries.get())

            append(" LeftRec=")
            append(RecoveryAuthorityRegistry.leftRecoveries.get())

            append(" RightRec=")
            append(RecoveryAuthorityRegistry.rightRecoveries.get())

            append(" GoalAreaRec=")
            append(RecoveryAuthorityRegistry.goalAreaRecoveries.get())
        }
    }
}
