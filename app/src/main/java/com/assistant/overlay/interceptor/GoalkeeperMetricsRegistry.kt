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
    // OmnipotentGoalkeeperEngine.lastRecoveryTarget) are NOT reset here —
    // they are self-correcting (a stale value only suppresses one
    // increment after a reset). (No66-D9 removed the second Omnipotent
    // tracker, lastClassifiedAction, with the split-brain increment.)
    fun reset() {
        triggerCount.set(0L)
        saveAttempts.set(0L)
        interceptions.set(0L)
        panicSaves.set(0L)
        recoveryCount.set(0L)
        crossClaims.set(0L)

        // No66-D3: a session reset must leave the keeper fully at rest,
        // not just zero the counters. The state machine kept its last
        // state (SAVE/RECOVERY) across a reset, so the snapshot showed a
        // stale State until the next threat; latestDecision likewise
        // stayed on the dashboard; and the router's episode tracker kept
        // its last action, so the first post-reset PUNCH/CLAIM/RUSH
        // episode could be swallowed (no increment). The router tracker
        // is reset here (it lives in this division); the Omnipotent
        // recovery tracker (lastRecoveryTarget) is NOT reset here — it
        // lives in OmnipotentGoalkeeperEngine, outside this division,
        // and stays self-correcting per the no64 note above (a stale
        // value there only suppresses one increment).
        GoalkeeperStateMachine.reset()
        GoalkeeperDecisionRegistry.latestDecision = null
        GoalkeeperActionRouter.resetEpisodeTracking()
    }

    fun snapshot(): String {

        return buildString {

            append("GK Trigger=")
            append(triggerCount.get())

            // No64-D28: this counts bus-accepted OFFERS, not dispatched
            // gestures — a cooling-dropped offer still counts (the
            // increment site is OmnipotentGoalkeeperEngine, on bus-accept).
            // Labelled as offers so the dashboard never reads as saves.
            // No66-D1: the router's two DIVE_BOTTOM saveAttempts
            // increments are removed, so this counter is now exactly
            // that: one increment per bus-accepted offer, single site.
            append(" SaveOffers=")
            append(saveAttempts.get())

            append(" Intercept=")
            append(interceptions.get())

            // No66-D4: crossClaims had no snapshot reader — its only
            // consumer was rushOutMultiplier(), so the dashboard could
            // never show it. Surfaced here next to Intercept. The two
            // counters are now disjoint by construction (No66-D9 fixed
            // the split-brain: the Omnipotent PUNCH/CLAIM increment is
            // removed, so the router is the single writer — RUSH_OUT
            // episodes go to Intercept, PUNCH/CLAIM episodes to
            // CrossClaims).
            append(" CrossClaims=")
            append(crossClaims.get())

            append(" Panic=")
            append(panicSaves.get())

            append(" Recovery=")
            append(recoveryCount.get())

            // No66-D4: the adaptive bonus / multiplier values themselves
            // were invisible — the dashboard could never show why a rush
            // started deeper or a CROSS flipped to INTERCEPT. Surface
            // all three (pure reads of the same counters above).
            append(" BonusI=")
            append(GoalkeeperAdaptiveFeedbackEngine.interceptionBonus())

            append(" BonusR=")
            append(GoalkeeperAdaptiveFeedbackEngine.recoveryBonus())

            append(" RushMult=")
            append(GoalkeeperAdaptiveFeedbackEngine.rushOutMultiplier())

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
