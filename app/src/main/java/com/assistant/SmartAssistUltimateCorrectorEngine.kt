package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object SmartAssistUltimateCorrectorEngine {
    private val outPass = FloatArray(3)
    private val outShot = FloatArray(4)
    private val outCross = FloatArray(4)
    private val outKeeper = FloatArray(3)

    fun correctPass(ballX: Float, ballY: Float, intendedX: Float, intendedY: Float, receiverVx: Float, receiverVy: Float, nearestOpponentX: Float, nearestOpponentY: Float, pressure: Float): SmartAssistCorrectionResult {
        val applied = com.assistant.NativeBridge.nativeComputeSACorrectPass(ballX, ballY, intendedX, intendedY, receiverVx, receiverVy, nearestOpponentX, nearestOpponentY, pressure, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outPass)
        return if (applied) SmartAssistCorrectionResult(outPass[0], outPass[1], outPass[2], true)
        else SmartAssistCorrectionResult(intendedX, intendedY, 0f, false)
    }

    fun correctShot(ballX: Float, ballY: Float, goalLeftX: Float, goalRightX: Float, goalTopY: Float, goalBottomY: Float, goalkeeperX: Float, goalkeeperVisible: Boolean, goalDetected: Boolean): SmartAssistCorrectionResult {
        val applied = com.assistant.NativeBridge.nativeComputeSACorrectShot(ballX, ballY, goalLeftX, goalRightX, goalTopY, goalBottomY, goalkeeperX, goalkeeperVisible, goalDetected, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outShot)
        return if (applied) SmartAssistCorrectionResult(outShot[0], outShot[1], outShot[2], true)
        else SmartAssistCorrectionResult(ballX, ballY, 0f, false)
    }

    fun correctCross(ballX: Float, ballY: Float, receiverX: Float, receiverY: Float, receiverVx: Float, receiverVy: Float, goalCenterX: Float, goalCenterY: Float, laneScore: Float): SmartAssistCorrectionResult {
        val applied = com.assistant.NativeBridge.nativeComputeSACorrectCross(ballX, ballY, receiverX, receiverY, receiverVx, receiverVy, goalCenterX, goalCenterY, laneScore, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outCross)
        return if (applied) SmartAssistCorrectionResult(outCross[0], outCross[1], outCross[2], true)
        else SmartAssistCorrectionResult(receiverX, receiverY, 0f, false)
    }

    fun correctKeeper(ballX: Float, ballY: Float, goalLeftX: Float, goalRightX: Float, goalTopY: Float, goalBottomY: Float, goalConfidence: Float = 0f): SmartAssistCorrectionResult {
        val applied = com.assistant.NativeBridge.nativeComputeSACorrectKeeper(ballX, ballY, goalLeftX, goalRightX, goalTopY, goalBottomY, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outKeeper)
        return if (applied) SmartAssistCorrectionResult(outKeeper[0], outKeeper[1], goalConfidence.coerceIn(0f, 1f), true)
        else SmartAssistCorrectionResult(ballX, ballY, 0f, false)
    }
}

/* ========
SpeedCompensationEngine
======== */
/**
 * High-fidelity representation of speed compensation vectors and intercept values.
 * Fields are upscaled up to 100.0f to deliver ultimate physical response times.
 */
