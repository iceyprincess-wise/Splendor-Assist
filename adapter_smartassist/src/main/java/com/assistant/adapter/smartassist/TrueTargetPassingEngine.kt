package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class PassingAssistResult(
    val correctedX: Float,
    val correctedY: Float,
    val interceptionRisk: Float
)

object TrueTargetPassingEngine {

    fun optimize(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        retention: Float
    ): PassingAssistResult {
        val dx = endX - startX
        val dy = endY - startY
        // FIX: old formula endX+(dx*0.65f*retention) overshot past receiver.
        // Correct: arrive proportionally at startX + dx * retention.
        return PassingAssistResult(
            correctedX       = (startX + dx * retention).coerceIn(0f, 1650f),
            correctedY       = (startY + dy * retention).coerceIn(0f, 720f),
            interceptionRisk = (1f - retention).coerceIn(0f, 1f)
        )
    }

    fun interceptionVector(
        ballX: Float, ballY: Float,
        ballVelocityX: Float, ballVelocityY: Float,
        receiverX: Float, receiverY: Float
    ): Pair<Float,Float> {
        val lookAhead = 0.3f
        val px = ballX + ballVelocityX * lookAhead
        val py = ballY + ballVelocityY * lookAhead
        val dx = px - receiverX; val dy = py - receiverY
        val m  = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (m > 0f) Pair((dx/m)*100f, (dy/m)*100f) else Pair(0f,0f)
    }

    fun calculateDoublePressEscapeVector(
        carrierX: Float, carrierY: Float,
        presserAX: Float, presserAY: Float,
        presserBX: Float, presserBY: Float,
        strikerX: Float, strikerY: Float
    ): Pair<Float,Float>? {
        val d = hypot((presserAX-presserBX).toDouble(),(presserAY-presserBY).toDouble())
        if (d < 80.0) {
            val mx=(presserAX+presserBX)/2f; val my=(presserAY+presserBY)/2f
            return Pair(carrierX+(strikerX-mx)*0.3f, carrierY+(strikerY-my)*0.3f)
        }
        return Pair(carrierX+(strikerX-carrierX)*0.35f, carrierY+(strikerY-carrierY)*0.35f)
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
