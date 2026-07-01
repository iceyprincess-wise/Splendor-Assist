package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class PassingAssistResult(
    val correctedX:Float,
    val correctedY:Float,
    val interceptionRisk:Float
)

object TrueTargetPassingEngine {

    fun optimize(
        startX:Float,
        startY:Float,
        endX:Float,
        endY:Float,
        retention:Float
    ):PassingAssistResult {

        val dx=endX-startX
        val dy=endY-startY

        return PassingAssistResult(
            correctedX=endX+(dx*0.65f*retention),
            correctedY=endY+(dy*0.65f*retention),
            interceptionRisk=(1f-retention).coerceIn(0f,1f)
        )
    }

    fun interceptionVector(
        ballX:Float,
        ballY:Float,
        ballVelocityX:Float,
        ballVelocityY:Float,
        receiverX:Float,
        receiverY:Float
    ):Pair<Float,Float> {

        val lookAhead = 0.3f

        val predictedBallX =
            ballX + (ballVelocityX * lookAhead)

        val predictedBallY =
            ballY + (ballVelocityY * lookAhead)

        val deltaX =
            predictedBallX - receiverX

        val deltaY =
            predictedBallY - receiverY

        val magnitude =
            hypot(
                deltaX.toDouble(),
                deltaY.toDouble()
            ).toFloat()

        return if (magnitude > 0f) {
            Pair(
                (deltaX / magnitude) * 100f,
                (deltaY / magnitude) * 100f
            )
        } else {
            Pair(0f,0f)
        }
    }

    fun calculateDoublePressEscapeVector(
        carrierX:Float,
        carrierY:Float,
        presserAX:Float,
        presserAY:Float,
        presserBX:Float,
        presserBY:Float,
        strikerX:Float,
        strikerY:Float
    ): Pair<Float,Float>? {

        val presserDistance =
            hypot(
                (presserAX - presserBX).toDouble(),
                (presserAY - presserBY).toDouble()
            )

        if (presserDistance < 80.0) {

            val midPointX =
                (presserAX + presserBX) / 2f

            val midPointY =
                (presserAY + presserBY) / 2f

            val escapeVectorX =
                strikerX - midPointX

            val escapeVectorY =
                strikerY - midPointY

            return Pair(
                carrierX + (escapeVectorX * 0.3f),
                carrierY + (escapeVectorY * 0.3f)
            )
        }

        return Pair(carrierX + ((strikerX-carrierX)*0.35f),carrierY + ((strikerY-carrierY)*0.35f))
    }


    fun currentPassingGraph(): PassingLaneGraph =
        Phase3WorldStateStore.current().passingGraph


    fun currentThroughBallAnalysis(): ThroughBallLaneAnalysis =
        Phase3WorldStateStore.current().throughBallAnalysis

    fun currentCrossingLaneAnalysis(): CrossingLaneAnalysis =
        Phase3WorldStateStore.current().crossingLaneAnalysis

    fun currentShootingLaneAnalysis(): ShootingLaneAnalysis =
        Phase3WorldStateStore.current().shootingLaneAnalysis

    fun currentBlockedLanePredictionAnalysis(): BlockedLanePredictionAnalysis =
        Phase3WorldStateStore.current().blockedLanePredictionAnalysis

    fun currentDefenderInterceptionPredictionAnalysis(): DefenderInterceptionPredictionAnalysis =
        Phase3WorldStateStore.current().defenderInterceptionPredictionAnalysis

    fun currentOpenSpaceDetectionResult(): OpenSpaceDetectionResult =
        Phase3WorldStateStore.current().openSpaceDetectionResult

    fun currentReceiverRankingResult(): ReceiverRankingResult =
        Phase3WorldStateStore.current().receiverRankingResult

    fun currentRunPredictionResult(): RunPredictionResult =
        Phase3WorldStateStore.current().runPredictionResult

    fun currentOverlapDetectionResult(): OverlapDetectionResult =
        Phase3WorldStateStore.current().overlapDetectionResult

    fun currentCounterattackDetectionResult(): CounterattackDetectionResult =
        Phase3WorldStateStore.current().counterattackDetectionResult

    fun currentFastBreakDetectionResult(): FastBreakDetectionResult =
        Phase3WorldStateStore.current().fastBreakDetectionResult

    fun currentOffsideRiskEstimationResult(): OffsideRiskEstimationResult =
        Phase3WorldStateStore.current().offsideRiskEstimationResult

}
