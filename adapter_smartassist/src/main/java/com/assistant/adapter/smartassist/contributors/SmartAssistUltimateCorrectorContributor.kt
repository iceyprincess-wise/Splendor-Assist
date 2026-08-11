package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.SmartAssistUltimateCorrectorEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * SmartAssistUltimateCorrectorContributor
 *
 * Always-on SA drift correction for every action type.
 * Decision tree per frame:
 *   hasBall + goalDetected + within 720px  -> SHOT  correction (open post)
 *   hasBall + viable pass lane             -> PASS  correction (run prediction + anti-drift)
 *   hasBall + viable cross lane            -> CROSS correction (receiver run lead)
 *   !hasBall + goalkeeper visible          -> KEEPER correction (ball-path intercept)
 */
object SmartAssistUltimateCorrectorContributor : GameplayContributor {
    override val engineName = "SAUltimateCorrector"
    override val capabilities = setOf(
        EngineCapability.ATTACK,
        EngineCapability.PASSING,
        EngineCapability.DEFENSE,
        EngineCapability.KEEPER
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null
        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }

        // SHOT
        if (frame.hasBall && frame.goalDetected) {
            val goalCX = (frame.goalLeftX + frame.goalRightX) * 0.5f
            val goalCY = (frame.goalTopY + frame.goalBottomY) * 0.5f
            val dist = hypot(
                (frame.ballX - goalCX).toDouble(),
                (frame.ballY - goalCY).toDouble()
            ).toFloat()
            if (dist <= 720f) {
                val c = SmartAssistUltimateCorrectorEngine.correctShot(
                    frame.ballX, frame.ballY,
                    frame.goalLeftX, frame.goalRightX,
                    frame.goalTopY, frame.goalBottomY,
                    frame.goalkeeperX, frame.goalkeeperVisible,
                    frame.goalDetected
                ) ?: return null
                return EngineContribution(
                    engine = engineName,
                    actionClass = ActionClass.SHOT,
                    targetX = c.correctedX,
                    targetY = c.correctedY,
                    authority = c.correctionStrength.coerceIn(0f, 1f),
                    confidence = frame.confidence,
                    durationHintMs = 28L
                )
            }
        }

        // PASS
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            val players = scene?.trackedPlayers.orEmpty()
            val receiver = players
                .filter { it.isUserTeam && !it.isGoalkeeper }
                .minByOrNull {
                    hypot(
                        (it.x - frame.passTargetX).toDouble(),
                        (it.y - frame.passTargetY).toDouble()
                    )
                }
            val opponent = players
                .filter { !it.isUserTeam }
                .minByOrNull {
                    hypot(
                        (it.x - frame.passTargetX).toDouble(),
                        (it.y - frame.passTargetY).toDouble()
                    )
                }
            val c = SmartAssistUltimateCorrectorEngine.correctPass(
                frame.ballX, frame.ballY,
                receiver?.x ?: frame.passTargetX,
                receiver?.y ?: frame.passTargetY,
                receiver?.velocityX ?: 0f,
                receiver?.velocityY ?: 0f,
                opponent?.x ?: frame.passTargetX,
                opponent?.y ?: frame.passTargetY,
                frame.defenderDensity
            )
            val authority = (c.correctionStrength * frame.bestLaneConfidence
                .coerceAtLeast(0.4f)).coerceIn(0f, 1f)
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.PASS,
                targetX = c.correctedX,
                targetY = c.correctedY,
                authority = authority,
                confidence = frame.confidence,
                durationHintMs = 38L
            )
        }

        // CROSS
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.bestLaneConfidence > 0f) {
            val players = scene?.trackedPlayers.orEmpty()
            val receiver = players
                .filter { it.isUserTeam && !it.isGoalkeeper }
                .minByOrNull {
                    hypot(
                        (it.x - frame.passTargetX).toDouble(),
                        (it.y - frame.passTargetY).toDouble()
                    )
                }
            val goalCX = if (frame.goalDetected)
                (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
            val goalCY = if (frame.goalDetected)
                (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY
            val c = SmartAssistUltimateCorrectorEngine.correctCross(
                frame.ballX, frame.ballY,
                receiver?.x ?: frame.passTargetX,
                receiver?.y ?: frame.passTargetY,
                receiver?.velocityX ?: 0f,
                receiver?.velocityY ?: 0f,
                goalCX, goalCY,
                frame.bestLaneConfidence
            ) ?: return null
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.CROSS,
                targetX = c.correctedX,
                targetY = c.correctedY,
                authority = c.correctionStrength.coerceIn(0f, 1f),
                confidence = frame.confidence,
                durationHintMs = 40L
            )
        }

        // KEEPER
        if (!frame.hasBall && frame.goalkeeperVisible) {
            val c = SmartAssistUltimateCorrectorEngine.correctKeeper(
                frame.ballX, frame.ballY,
                frame.goalLeftX, frame.goalRightX,
                frame.goalTopY, frame.goalBottomY
            )
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.KEEPER,
                targetX = c.correctedX,
                targetY = c.correctedY,
                authority = c.correctionStrength.coerceIn(0f, 1f),
                confidence = frame.confidence,
                durationHintMs = 24L
            )
        }

        return null
    }
}
