package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.DefenseAuthorityEngine
import com.assistant.adapter.smartassist.Phase3WorldStateStore
import com.assistant.runtime.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object DefenseAuthorityContributor : GameplayContributor {
    override val engineName = "DefenseAuthority"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

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
            val goalX = 0f
            val goalY = 540f
            val refX = defX ?: ballX
            val refY = defY ?: ballY

            val dx = (goalX - refX).toDouble()
            val dy = (goalY - refY).toDouble()
            val angle = atan2(dy, dx)
            val containOffset = 60f + ((1f - (distance / 1200f)) * 40f)

            targetX = (refX + cos(angle).toFloat() * containOffset).coerceIn(0f, 1650f)
            targetY = (refY + sin(angle).toFloat() * containOffset).coerceIn(0f, 1080f)
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
