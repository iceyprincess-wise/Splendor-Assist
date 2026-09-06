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
 * Core mathematical engine for fixing Smart Assist drift across all action types.
 * Performs zero heap allocations during math evaluations.
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
        val dx = intendedX - ballX
        val dy = intendedY - ballY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
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
        val minX = if (goalLeftX <= goalRightX) goalLeftX else goalRightX
        val maxX = if (goalLeftX <= goalRightX) goalRightX else goalLeftX
        val minY = if (goalTopY <= goalBottomY) goalTopY else goalBottomY
        val maxY = if (goalTopY <= goalBottomY) goalBottomY else goalTopY

        val goalCX = if (goalDetected) (minX + maxX) * 0.5f else 1650f
        val goalCY = if (goalDetected) (minY + maxY) * 0.5f else ballY
        
        val dist = hypot((ballX - goalCX).toDouble(), (ballY - goalCY).toDouble()).toFloat()
        if (dist > MAX_SHOT_DIST) return null

        val openX: Float
        val openY: Float = goalCY
        if (goalDetected && goalkeeperVisible && goalkeeperX > 0f) {
            openX = if (goalkeeperX <= goalCX) {
                (goalCX + (maxX - goalCX) * 0.70f).coerceIn(minX, maxX)
            } else {
                (goalCX - (goalCX - minX) * 0.70f).coerceIn(minX, maxX)
            }
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
        val minY = if (goalTopY <= goalBottomY) goalTopY else goalBottomY
        val maxY = if (goalTopY <= goalBottomY) goalBottomY else goalTopY
        val goalMidY = if (minY > 0f && maxY > minY) (minY + maxY) * 0.5f else ballY

        val gl = if (goalLeftX <= goalRightX) goalLeftX else goalRightX
        val gr = if (goalLeftX <= goalRightX) goalRightX else goalLeftX
        val interceptX = ballX.coerceIn(gl.coerceAtLeast(0f), gr.coerceAtMost(1650f))

        return SmartAssistCorrectionResult(interceptX, goalMidY, 0.92f, CorrectionType.KEEPER, true)
    }
}
