package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.SmartAssistUltimateCorrectorEngine
import com.assistant.adapter.smartassist.TrackedPlayer
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * SmartAssistUltimateCorrectorContributor
 *
 * Zero-allocation always-on contributor for correcting eFootball Smart Assist drift.
 * Eliminates lambda/filter object creation inside 60 FPS hot paths.
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
        val scene = SceneTracker.current()

        // SHOT Evaluation
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

        // PASS Evaluation
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            val players = scene.trackedPlayers
            var receiver: TrackedPlayer? = null
            var opponent: TrackedPlayer? = null
            var minReceiverDist = Float.MAX_VALUE
            var minOpponentDist = Float.MAX_VALUE

            if (players != null) {
                val size = players.size
                for (i in 0 until size) {
                    val p = players[i]
                    val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                    
                    if (p.isUserTeam && !p.isGoalkeeper) {
                        if (d < minReceiverDist) {
                            minReceiverDist = d
                            receiver = p
                        }
                    } else if (!p.isUserTeam) {
                        if (d < minOpponentDist) {
                            minOpponentDist = d
                            opponent = p
                        }
                    }
                }
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
            
            val authority = (c.correctionStrength * frame.bestLaneConfidence.coerceAtLeast(0.4f)).coerceIn(0f, 1f)
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

        // CROSS Evaluation
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.bestLaneConfidence > 0f) {
            val players = scene.trackedPlayers
            var receiver: TrackedPlayer? = null
            var minReceiverDist = Float.MAX_VALUE

            if (players != null) {
                val size = players.size
                for (i in 0 until size) {
                    val p = players[i]
                    if (p.isUserTeam && !p.isGoalkeeper) {
                        val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                        if (d < minReceiverDist) {
                            minReceiverDist = d
                            receiver = p
                        }
                    }
                }
            }

            val goalCX = if (frame.goalDetected) (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
            val goalCY = if (frame.goalDetected) (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY

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

        // KEEPER Evaluation
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
