package com.assistant.contributors

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

/*
 * Threat-priority defensive claim.
 *
 * Lives in the app module because the interceptor engines do. Threat inputs are
 * derived from RuntimeFrame only -- never from another contributor.
 *
 * CollisionAvoidanceEngine and OwnGoalAvoidanceEngine are used here as GATES,
 * not as competing claims: a veto must suppress an action, never cause one.
 */
object ThreatPriorityContributor : GameplayContributor {
    override val engineName = "ThreatPriority"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.KEEPER)

    // No67-D29: throttled failure logging — collect() runs single-threaded
    // under @Synchronized onFrame(), so plain vars are enough.
    private var decisionFailureCount = 0
    private var gateFailureCount = 0

    internal fun zoneOf(frame: RuntimeFrame): ThreatZone {
        val w = if (frame.captureWidth > 0f) frame.captureWidth
            else com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val h = if (frame.captureHeight > 0f) frame.captureHeight
            else com.assistant.vision.CameraProfile.captureHeightOrFallback()
        // SPLENDOR_P10_ZONE: the goal sits at an X end of the frame
        // (GoalDetector searches the left/right ends; attack direction
        // is X everywhere it is consumed). L12 (batch 1): the Model-X
        // logic now lives in ThreatZoneEngine.zoneOfBall (shared with
        // the keeper scan); this delegates so the two paths cannot drift.
        // ThreatZoneEngine.detect(), the scan strip and the six-yard box
        // are deliberately UNCHANGED (gauge-pixel input, not ball
        // position).
        return ThreatZoneEngine.zoneOfBall(
            frame.ballX, frame.ballY,
            frame.goalLeftX, frame.goalRightX, frame.goalDetected, w, h
        )
    }

    internal fun threatOf(frame: RuntimeFrame): ThreatType {
        // L10 (batch 1): the learned opponent aggression lifts the heat,
        // at most +0.10, scaled by the learning confidence — with no
        // learning (confidence 0) the heat is exactly as before.
        val learnedLift = frame.opponentAggression * 0.10f * frame.opponentBehaviourConfidence
        val heat = (frame.defenderDensity * 0.6f + frame.confidence * 0.4f) + learnedLift
        return when {
            heat >= 0.85f -> ThreatType.PURPLE
            heat >= 0.70f -> ThreatType.GREEN
            heat >= 0.55f -> ThreatType.RED
            heat >= 0.40f -> ThreatType.ORANGE
            heat >= 0.25f -> ThreatType.YELLOW
            heat >  0.05f -> ThreatType.WHITE
            else          -> ThreatType.NONE
        }
    }

    internal fun decisionOf(frame: RuntimeFrame): ThreatDecision? {
        val threat = threatOf(frame)
        if (threat == ThreatType.NONE) return null
        // F38-D4: SPLENDOR_P10_ZONE_LOG removed — the experiment is
        // decided. The 2026-11-16 live session (8,018 zoneOf/detect
        // pairs, goalCX at the X ends) proved Model X: the goal sits at
        // an X end of the frame. ThreatZoneEngine.detect() is KEPT
        // unchanged: its only remaining caller is the GK scan strip
        // (OmnipotentGoalkeeperEngine), which feeds it gauge-animation
        // pixels in the bottom 25% of the frame, not ball position.
        val p10Zone = zoneOf(frame)
        return try {
            ThreatPriorityEngine.evaluate(
                threat,
                p10Zone,
                frame.ballX.toInt(),
                frame.ballY.toInt(),
                frame.captureWidth.toInt().takeIf { it > 0 }
                    ?: com.assistant.vision.CameraProfile.captureWidthOrFallback().toInt(),
                frame.captureHeight.toInt().takeIf { it > 0 }
                    ?: com.assistant.vision.CameraProfile.captureHeightOrFallback().toInt(),
                // Q68-86 (batch 5b — D15): live ball trajectory speed so
                // LONG_BALL is trajectory-gated, not a catch-all.
                com.assistant.BallTrajectoryPredictor.current().speed
            )
        } catch (_: Throwable) {
            decisionFailureCount += 1
            if (decisionFailureCount % 50 == 1) RuntimeLogger.log("ThreatPriority decisionOf failed x$decisionFailureCount", "THREAT")
            null
        }
    }

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // Interception switch gate (f5): when the user turns Interception off the
        // threat-priority claim is withdrawn at the live contributor entry.
        if (!InterceptionRuntimeRegistry.enabled) return null
        if (frame.confidence < 0.05f) return null
        val possessionWeight = if (!frame.hasBall) 1.0f else 0.3f // Defensive focus when opponent has ball
        // RF-D9 (batch 5): the trustWeight fallback (0.6f on untrusted) was
        // dead — GameplayEngineRegistry.collect() returns empty before
        // contributors on an untrusted frame, so frame.trusted is always
        // true here (same as ShotContributor's No68-F-D14 fix).
        val fluidMultiplier = possessionWeight
        val decision = decisionOf(frame) ?: return null

        // GATE 1: collision risk may veto a rush.
        val rushAllowed = try {
            CollisionAvoidanceEngine.allowRush(
                CollisionAvoidanceEngine.evaluate(decision)
            )
        } catch (_: Throwable) {
            gateFailureCount += 1
            if (gateFailureCount % 50 == 1) RuntimeLogger.log("ThreatPriority gate failed x$gateFailureCount", "THREAT")
            false
        }
        if (!rushAllowed) return null

        // GATE 2: own-goal safety may veto execution entirely.
        val safeToExecute = try {
            OwnGoalAvoidanceEngine.allowExecution(
                OwnGoalAvoidanceEngine.evaluate(decision.direction, decision.zone)
            )
        } catch (_: Throwable) {
            gateFailureCount += 1
            if (gateFailureCount % 50 == 1) RuntimeLogger.log("ThreatPriority gate failed x$gateFailureCount", "THREAT")
            false
        }
        if (!safeToExecute) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = ((decision.priority / 150f) * fluidMultiplier).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
