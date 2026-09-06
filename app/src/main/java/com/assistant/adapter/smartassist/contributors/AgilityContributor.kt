package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.AgilityEngine
import com.assistant.adapter.smartassist.Phase3WorldStateStore
import com.assistant.runtime.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        var oppX: Float? = null
        var oppY: Float? = null
        var opponentDistance = (1f - frame.defenderDensity) * 300f

        try {
            val def = Phase3WorldStateStore.current().defender
            if (def.found && def.distanceToAttacker < Float.MAX_VALUE) {
                opponentDistance = def.distanceToAttacker.coerceIn(0f, 1200f)
                oppX = def.x
                oppY = def.y
            }
        } catch (_: Throwable) {
            // Safe store extraction fallback
        }

        val ballX = frame.ballX
        val ballY = frame.ballY

        val movementAngle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = (frame.passTargetX - ballX).toDouble()
            val dy = (frame.passTargetY - ballY).toDouble()
            Math.toDegrees(atan2(dy, dx)).toFloat()
        } else {
            0f
        }

        // Scaled velocity estimation for 15.0f max velocity ceiling
        val estimatedVelocity = (frame.bestLaneConfidence * 10.0f + frame.confidence * 5.0f).coerceIn(0f, 15f)
        val turnIntensity = frame.defenderDensity.coerceIn(0f, 1f)

        val result = AgilityEngine.computeAgility(
            playerVelocity = estimatedVelocity,
            opponentDistance = opponentDistance,
            movementAngleDegrees = movementAngle,
            possessionConfidence = frame.confidence,
            turnIntensity = turnIntensity,
            playerX = ballX,
            playerY = ballY,
            oppX = oppX,
            oppY = oppY
        )

        // Authority incorporates stability boost and control retention
        val baseAuthority = (result.stabilityBoost / 10f) + (result.controlRetentionBoost * 0.2f)
        val authority = baseAuthority.coerceIn(0.40f, 1.0f)

        // Evasive target calculation during high-pressure close dribbling
        val targetX: Float
        val targetY: Float

        if (frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            targetX = frame.passTargetX
            targetY = if (frame.passTargetY > 0f) frame.passTargetY else ballY
        } else {
            // Intelligent body-shield evasive targeting when no open lane exists
            if (oppX != null && oppY != null && opponentDistance < 250f) {
                val shieldRad = Math.toRadians(result.shieldAngleDegrees.toDouble())
                val pushDist = 75f + (result.turnAssist * 45f)
                targetX = (ballX + cos(shieldRad).toFloat() * pushDist).coerceIn(0f, 1650f)
                targetY = (ballY + sin(shieldRad).toFloat() * pushDist).coerceIn(0f, 1080f)
            } else {
                val yOffset = if (result.turnAssist > 0.2f) {
                    if (ballY > 540f) -40f else 40f
                } else {
                    0f
                }
                targetX = (ballX + 85f).coerceIn(0f, 1650f)
                targetY = (ballY + yOffset).coerceIn(0f, 1080f)
            }
        }

        val duration = result.shieldDurationMs.coerceIn(16L, 100L)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = duration
        )
    }
}
