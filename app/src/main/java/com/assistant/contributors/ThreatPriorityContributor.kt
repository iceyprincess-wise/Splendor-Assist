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

    internal fun zoneOf(frame: RuntimeFrame): ThreatZone = when {
        frame.ballX > 1450f -> ThreatZone.GOAL_AREA
        frame.ballX > 1250f -> ThreatZone.BOX
        frame.ballY < 240f  -> ThreatZone.LEFT
        frame.ballY > 480f  -> ThreatZone.RIGHT
        else                -> ThreatZone.CENTER
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
        return try {
            ThreatPriorityEngine.evaluate(threat, zoneOf(frame))
        } catch (_: Throwable) { null }
    }

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val decision = decisionOf(frame) ?: return null

        // GATE 1: collision risk may veto a rush.
        val rushAllowed = try {
            CollisionAvoidanceEngine.allowRush(
                CollisionAvoidanceEngine.evaluate(decision)
            )
        } catch (_: Throwable) { true }
        if (!rushAllowed) return null

        // GATE 2: own-goal safety may veto execution entirely.
        val safeToExecute = try {
            OwnGoalAvoidanceEngine.allowExecution(
                OwnGoalAvoidanceEngine.evaluate(decision.direction, decision.zone)
            )
        } catch (_: Throwable) { true }
        if (!safeToExecute) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (decision.priority / 135f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
