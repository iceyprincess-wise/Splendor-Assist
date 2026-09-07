package com.assistant.adapter.smartassist

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PassingAssistResult(
    val correctedX: Float,
    val correctedY: Float,
    val interceptionRisk: Float,
    val requiresEscapeVector: Boolean = false
)

object TrueTargetPassingEngine {

    /**
     * @param retention 0..1 fraction along (start->end) to land on.
     * BUG FIX: retention now clamped. Old callers passed * 10f -> 10x overshoot.
     */
    fun optimize(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        retention: Float
    ): PassingAssistResult {
        val r = retention.coerceIn(0f, 1f)
        val dx = endX - startX
        val dy = endY - startY
        return PassingAssistResult(
            correctedX       = (startX + dx * r).coerceIn(0f, 1650f),
            correctedY       = (startY + dy * r).coerceIn(0f, 720f),
            interceptionRisk = (1f - r).coerceIn(0f, 1f)
        )
    }

    /**
     * Predict where receiver will be when ball arrives, aim there.
     * Defeats SA interception where receiver runs away from ball path.
     */
    fun optimizeWithRunPrediction(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float
    ): PassingAssistResult {
        val dist = hypot(
            (receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()
        ).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = 60f
        val predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        val predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)
        return optimize(ballX, ballY, predX, predY, 1.0f)
    }

    /**
     * Advanced Run & Match-Up Interception Escape Optimization
     */
    fun optimizeWithAdvancedTactics(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float,
        defenderDensity: Float
    ): PassingAssistResult {
        val dist = hypot((receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = 60f
        
        var predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        var predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)

        // Match-Up / Defender Shadow Offset
        if (defenderDensity > 0.5f) {
            val passAngle = atan2((predY - ballY).toDouble(), (predX - ballX).toDouble())
            // Offset pass vector 25px orthogonal to defender density cone
            predX += (sin(passAngle) * 25.0).toFloat()
            predY -= (cos(passAngle) * 25.0).toFloat()
        }

        val risk = (defenderDensity * 0.7f).coerceIn(0f, 1f)

        return PassingAssistResult(
            correctedX = predX.coerceIn(0f, 1650f),
            correctedY = predY.coerceIn(0f, 720f),
            interceptionRisk = risk,
            requiresEscapeVector = defenderDensity > 0.8f
        )
    }

    fun interceptionVector(
        ballX: Float, ballY: Float,
        ballVelocityX: Float, ballVelocityY: Float,
        receiverX: Float, receiverY: Float
    ): Pair<Float, Float> {
        val lookAhead = 0.3f
        val px = ballX + ballVelocityX * lookAhead
        val py = ballY + ballVelocityY * lookAhead
        val dx = px - receiverX
        val dy = py - receiverY
        val m = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (m > 0f) Pair((dx / m) * 100f, (dy / m) * 100f) else Pair(0f, 0f)
    }

    fun calculateDoublePressEscapeVector(
        carrierX: Float, carrierY: Float,
        presserAX: Float, presserAY: Float,
        presserBX: Float, presserBY: Float,
        strikerX: Float, strikerY: Float
    ): Pair<Float, Float> {
        val d = hypot((presserAX - presserBX).toDouble(), (presserAY - presserBY).toDouble())
        return if (d < 80.0) {
            val mx = (presserAX + presserBX) / 2f
            val my = (presserAY + presserBY) / 2f
            Pair(carrierX + (strikerX - mx) * 0.3f, carrierY + (strikerY - my) * 0.3f)
        } else {
            Pair(carrierX + (strikerX - carrierX) * 0.35f, carrierY + (strikerY - carrierY) * 0.35f)
        }
    }

    fun currentPassingGraph()                        = Phase3WorldStateStore.current().passingGraph
    fun currentThroughBallAnalysis()                 = Phase3WorldStateStore.current().throughBallAnalysis
    fun currentCrossingLaneAnalysis()                = Phase3WorldStateStore.current().crossingLaneAnalysis
    fun currentShootingLaneAnalysis()                = Phase3WorldStateStore.current().shootingLaneAnalysis
    fun currentBlockedLanePredictionAnalysis()       = Phase3WorldStateStore.current().blockedLanePredictionAnalysis
    fun currentDefenderInterceptionPredictionAnalysis() = Phase3WorldStateStore.current().defenderInterceptionPredictionAnalysis
    fun currentOpenSpaceDetectionResult()            = Phase3WorldStateStore.current().openSpaceDetectionResult
    fun currentReceiverRankingResult()               = Phase3WorldStateStore.current().receiverRankingResult
    fun currentRunPredictionResult()                 = Phase3WorldStateStore.current().runPredictionResult
    fun currentOverlapDetectionResult()              = Phase3WorldStateStore.current().overlapDetectionResult
    fun currentCounterattackDetectionResult()        = Phase3WorldStateStore.current().counterattackDetectionResult
    fun currentFastBreakDetectionResult()            = Phase3WorldStateStore.current().fastBreakDetectionResult
    fun currentOffsideRiskEstimationResult()         = Phase3WorldStateStore.current().offsideRiskEstimationResult
}
