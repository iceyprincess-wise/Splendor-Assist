package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*


import kotlin.math.atan2

import kotlin.math.cos







import kotlin.math.sin















object DefenseAuthorityContributor : GameplayContributor {
    override val engineName = "DefenseAuthority"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        // No68-D-D8: no presence gate. defenderDensity is the opponent
        // share (FrameAssembler: opponents / players), so 0 means no
        // opponent is visible at all — yet the old code still built a
        // DEFEND claim from vision confidence alone (authority up to
        // ~0.79 at confidence 0.9) that could outrank BallPress and win
        // arbitration with nothing to defend against.
        if (frame.defenderDensity <= 0f) return null

        var distance = (1f - frame.defenderDensity) * 500f
        var defX: Float? = null
        var defY: Float? = null

        try {
            val defState = Phase3WorldStateStore.current().defender
            if (defState.found && defState.distanceToAttacker < Float.MAX_VALUE) {
                distance = defState.distanceToAttacker.coerceIn(0f, 1200f)
                val trackedDef = defState.defender
                if (trackedDef != null) {
                    defX = trackedDef.x
                    defY = trackedDef.y
                }
            }
        } catch (_: Throwable) {
            // World state store fallback
        }

        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val recovery = frame.confidence * 10f
        val retention = frame.bestLaneConfidence * 10f

        val r = DefenseAuthorityEngine.evaluate(distance, strength, recovery, retention)

        // Rescaled authority math: maps 0..10 score spectrum cleanly to 0.0..1.0 range
        val normalizedContainment = r.containment / 10f
        val normalizedInterception = r.interception / 10f
        val rawAuthority = (normalizedContainment * 0.5f) + (normalizedInterception * 0.5f)
        val authority = rawAuthority.coerceIn(0.10f, 1.0f)

        val ballX = frame.ballX
        val ballY = frame.ballY

        val targetX: Float
        val targetY: Float

        if (r.containment > r.interception) {
            // Containment positioning: vector goal-side using defender coordinates when present
            // Positive user attack points right, so our own goal is the left edge.
            // Low-confidence inference preserves the historical left-edge target.
            val attackDirection = AttackAxisModel.directionIfConfident()
            val fallbackGoalX = 0f
            val goalX = if (attackDirection > 0) fallbackGoalX else if (attackDirection < 0) com.assistant.vision.CameraProfile.captureWidthOrFallback() else fallbackGoalX
            // No68-D-D9: was a hardcoded 540f. On the 720-high fallback
            // that is 0.75h; on the live 640-high capture it is 0.84h —
            // in neither space is it the goal line. Use the detected
            // goal's real centre (frame.goalCenterY, carried from the
            // goal detector via FrameAssembler) and fall back to the
            // vertical centre of the capture when no goal is detected.
            val goalY = if (frame.goalDetected) frame.goalCenterY
                else com.assistant.vision.CameraProfile.captureHeightOrFallback() * 0.5f
            val refX = defX ?: ballX
            val refY = defY ?: ballY

            val dx = (goalX - refX).toDouble()
            val dy = (goalY - refY).toDouble()
            val angle = atan2(dy, dx)
            val containOffset = 60f + ((1f - (distance / 1200f)) * 40f)

            targetX = (refX + cos(angle).toFloat() * containOffset).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
            targetY = (refY + sin(angle).toFloat() * containOffset).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        } else {
            // Direct press / interception targeting
            targetX = ballX
            targetY = ballY
        }

        return EngineContribution(
            engineName,
            ActionClass.DEFEND,
            targetX,
            targetY,
            authority,
            frame.confidence,
            32L
        )
    }
}

/* ========
DefenseContributor
======== */
