package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class SmartAssistCorrectionResult(
    val correctedX: Float,
    val correctedY: Float,
    val correctionStrength: Float,
    val correctionType: CorrectionType,
    val applied: Boolean
)

enum class CorrectionType { PASS, SHOT, CROSS, KEEPER, NONE }

/**
 * SmartAssistUltimateCorrectorEngine
 *
 * Corrects every type of eFootball Smart Assist drift:
 *
 *   PASS  - SA deflects toward nearest opponent, NOT intended receiver.
 *            Receiver runs away so the ball goes to empty space.
 *            Fix: predict receiver future position + shift away from SA pull.
 *
 *   SHOT  - SA redirects to a safe zone; ball flies wide even on-target input.
 *            Fix: aim the OPEN post opposite the goalkeeper.
 *
 *   CROSS - SA aims at current receiver position; receiver runs past it,
 *            defender fills the gap, interception every time.
 *            Fix: predict where receiver WILL BE when ball arrives, aim there.
 *
 *   KEEPER - SA positions GK incorrectly, does not track ball path.
 *            Fix: clamp GK to ball horizontal path inside goal width.
 */
object SmartAssistUltimateCorrectorEngine {

    private const val SA_PASS_ANTI_DRIFT = 0.40f
    private const val BALL_SPEED_PX_S    = 800f
    private const val PASS_LOOKAHEAD_S   = 0.16f
    private const val CROSS_SPEED_PX_S   = 700f
    private const val MAX_SHOT_DIST      = 720f

    fun correctPass(
        ballX: Float, ballY: Float,
        intendedX: Float, intendedY: Float,
        receiverVx: Float, receiverVy: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        pressure: Float
    ): SmartAssistCorrectionResult {
        val dist = hypot((intendedX - ballX).toDouble(), (intendedY - ballY).toDouble()).toFloat()
        val travelS = (dist / BALL_SPEED_PX_S + PASS_LOOKAHEAD_S).coerceIn(0f, 0.55f)
        val fps = 60f
        val predX = (intendedX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        val predY = (intendedY + receiverVy * fps * travelS).coerceIn(0f, 720f)
        val saDx = nearestOpponentX - intendedX
        val saDy = nearestOpponentY - intendedY
        val correctedX = (predX - saDx * SA_PASS_ANTI_DRIFT).coerceIn(0f, 1650f)
        val correctedY = (predY - saDy * SA_PASS_ANTI_DRIFT).coerceIn(0f, 720f)
        val strength = (0.55f + pressure * 0.45f).coerceIn(0f, 1f)
        return SmartAssistCorrectionResult(correctedX, correctedY, strength, CorrectionType.PASS, true)
    }

    fun correctShot(
        ballX: Float, ballY: Float,
        goalLeftX: Float, goalRightX: Float,
        goalTopY: Float, goalBottomY: Float,
        goalkeeperX: Float, goalkeeperVisible: Boolean,
        goalDetected: Boolean
    ): SmartAssistCorrectionResult? {
        val goalCX = if (goalDetected) (goalLeftX + goalRightX) * 0.5f else 1650f
        val goalCY = if (goalDetected) (goalTopY + goalBottomY) * 0.5f else ballY
        val dist = hypot((ballX - goalCX).toDouble(), (ballY - goalCY).toDouble()).toFloat()
        if (dist > MAX_SHOT_DIST) return null
        val openX: Float
        val openY: Float = goalCY
        if (goalDetected && goalkeeperVisible && goalkeeperX > 0f) {
            openX = if (goalkeeperX <= goalCX)
                (goalCX + (goalRightX - goalCX) * 0.70f).coerceIn(goalLeftX, goalRightX)
            else
                (goalCX - (goalCX - goalLeftX) * 0.70f).coerceIn(goalLeftX, goalRightX)
        } else {
            openX = goalCX
        }
        val proximity = 1f - (dist / MAX_SHOT_DIST)
        val strength = (0.70f + proximity * 0.30f).coerceIn(0f, 1f)
        return SmartAssistCorrectionResult(
            openX.coerceIn(0f, 1650f), openY.coerceIn(0f, 720f),
            strength, CorrectionType.SHOT, true
        )
    }

    fun correctCross(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float,
        goalCenterX: Float, goalCenterY: Float,
        laneScore: Float
    ): SmartAssistCorrectionResult? {
        val dist = hypot((receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()).toFloat()
        if (dist > 900f || laneScore < 0.04f) return null
        val travelS = (dist / CROSS_SPEED_PX_S + 0.06f).coerceIn(0f, 0.55f)
        val fps = 60f
        val predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        val predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)
        val distPredToGoal = hypot(
            (predX - goalCenterX).toDouble(), (predY - goalCenterY).toDouble()
        ).toFloat()
        val targetX: Float
        val targetY: Float
        if (distPredToGoal < 220f) {
            targetX = predX
            targetY = predY
        } else {
            val penX = (goalCenterX - 160f).coerceIn(0f, 1650f)
            targetX = (predX * 0.55f + penX * 0.45f).coerceIn(0f, 1650f)
            targetY = (predY * 0.55f + goalCenterY * 0.45f).coerceIn(0f, 720f)
        }
        val strength = (laneScore * 0.75f + 0.25f * (1f - dist / 900f)).coerceIn(0f, 1f)
        return SmartAssistCorrectionResult(targetX, targetY, strength, CorrectionType.CROSS, true)
    }

    fun correctKeeper(
        ballX: Float, ballY: Float,
        goalLeftX: Float, goalRightX: Float,
        goalTopY: Float, goalBottomY: Float
    ): SmartAssistCorrectionResult {
        val goalMidY = if (goalTopY > 0f && goalBottomY > goalTopY)
            (goalTopY + goalBottomY) * 0.5f else ballY
        val gl = goalLeftX.coerceAtLeast(0f)
        val gr = goalRightX.coerceAtMost(1650f).coerceAtLeast(gl)
        val interceptX = ballX.coerceIn(gl, gr)
        return SmartAssistCorrectionResult(interceptX, goalMidY, 0.92f, CorrectionType.KEEPER, true)
    }
}
