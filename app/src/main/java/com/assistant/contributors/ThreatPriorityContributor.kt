package com.assistant.contributors

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

    internal fun zoneOf(frame: RuntimeFrame): ThreatZone {
        val w = if (frame.captureWidth > 0f) frame.captureWidth
            else com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val h = if (frame.captureHeight > 0f) frame.captureHeight
            else com.assistant.vision.CameraProfile.captureHeightOrFallback()
        // SPLENDOR_P10_ZONE: the goal sits at an X end of the frame
        // (GoalDetector searches the left/right ends; attack direction
        // is X everywhere it is consumed). Prefer the detected goal
        // span when present; the BOX band extends one old band-width
        // (0.12 of width: 0.88 - 0.76) beyond each end of the span.
        // Fall back to either-end width fractions when no goal is
        // detected -- the old code only covered the right end, so in
        // halves defending the left goal GOAL_AREA/BOX never fired.
        // LEFT/RIGHT stay by ballY (far/near side). ThreatZoneEngine
        // .detect(), the scan strip and the six-yard box are deliberately
        // UNCHANGED (gauge-pixel input, not ball position).
        if (frame.goalDetected && frame.goalRightX > frame.goalLeftX) {
            if (frame.ballX >= frame.goalLeftX && frame.ballX <= frame.goalRightX) return ThreatZone.GOAL_AREA
            val boxMargin = w * 0.12f
            if (frame.ballX >= frame.goalLeftX - boxMargin && frame.ballX <= frame.goalRightX + boxMargin) return ThreatZone.BOX
        } else {
            if (frame.ballX > w * 0.88f || frame.ballX < w * 0.12f) return ThreatZone.GOAL_AREA
            if (frame.ballX > w * 0.76f || frame.ballX < w * 0.24f) return ThreatZone.BOX
        }
        return when {
            frame.ballY < h * 0.33f -> ThreatZone.LEFT
            frame.ballY > h * 0.67f -> ThreatZone.RIGHT
            else -> ThreatZone.CENTER
        }
    }

    internal fun threatOf(frame: RuntimeFrame): ThreatType {
        val heat = (frame.defenderDensity * 0.6f + frame.confidence * 0.4f)
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
        // SPLENDOR_P10_ZONE_LOG: temporary, remove after live
        // confirmation. Compares the fixed ball-based zone against
        // ThreatZoneEngine.detect for the same frame, so one live
        // session decides the axis (ballX clustering near 0 or W with
        // the goal detected at that end confirms Model X).
        val p10Zone = zoneOf(frame)
        val p10CapW = frame.captureWidth.toInt().takeIf { it > 0 }
            ?: com.assistant.vision.CameraProfile.captureWidthOrFallback().toInt()
        val p10CapH = frame.captureHeight.toInt().takeIf { it > 0 }
            ?: com.assistant.vision.CameraProfile.captureHeightOrFallback().toInt()
        try {
            com.assistant.diagnostic.RuntimeLogger.log(
                "GK_ZONE_BALL zoneOf=" + p10Zone +
                    " detect=" + ThreatZoneEngine.detect(frame.ballX.toInt(), frame.ballY.toInt(), p10CapW, p10CapH) +
                    " ball=(" + frame.ballX + "," + frame.ballY + ")" +
                    " goalDet=" + frame.goalDetected + " goalCX=" + frame.goalCenterX +
                    " goalConf=" + frame.goalConfidence + " atkDir=" + frame.attackDirection +
                    " cap=" + frame.captureWidth + "x" + frame.captureHeight, "GOALKEEPER")
        } catch (_: Throwable) { }
        return try {
            ThreatPriorityEngine.evaluate(
                threat,
                p10Zone,
                frame.ballX.toInt(),
                frame.ballY.toInt(),
                frame.captureWidth.toInt().takeIf { it > 0 }
                    ?: com.assistant.vision.CameraProfile.captureWidthOrFallback().toInt(),
                frame.captureHeight.toInt().takeIf { it > 0 }
                    ?: com.assistant.vision.CameraProfile.captureHeightOrFallback().toInt()
            )
        } catch (_: Throwable) { null }
    }

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // Interception switch gate (f5): when the user turns Interception off the
        // threat-priority claim is withdrawn at the live contributor entry.
        if (!InterceptionRuntimeRegistry.enabled) return null
        if (frame.confidence < 0.05f) return null
        val possessionWeight = if (!frame.hasBall) 1.0f else 0.3f // Defensive focus when opponent has ball
        val trustWeight = if (frame.trusted) 1.0f else 0.6f
        val fluidMultiplier = possessionWeight * trustWeight
        val decision = decisionOf(frame) ?: return null

        // GATE 1: collision risk may veto a rush.
        val rushAllowed = try {
            CollisionAvoidanceEngine.allowRush(
                CollisionAvoidanceEngine.evaluate(decision)
            )
        } catch (_: Throwable) { false }
        if (!rushAllowed) return null

        // GATE 2: own-goal safety may veto execution entirely.
        val safeToExecute = try {
            OwnGoalAvoidanceEngine.allowExecution(
                OwnGoalAvoidanceEngine.evaluate(decision.direction, decision.zone)
            )
        } catch (_: Throwable) { false }
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
