package com.assistant.adapter.smartassist

object GoalkeeperTrajectoryPredictor {

    data class Prediction(
        val currentX: Float = 0f,
        val currentY: Float = 0f,
        val velocityX: Float = 0f,
        val velocityY: Float = 0f,
        val headingRadians: Float = 0f,
        val predictedX: Float = 0f,
        val predictedY: Float = 0f,
        val confidence: Float = 0f
    )

    private var latest = Prediction()

    fun update(
        currentX: Float,
        currentY: Float,
        velocityX: Float,
        velocityY: Float,
        headingRadians: Float,
        confidence: Float
    ) {
        val lookAheadFrames = 6f

        latest = Prediction(
            currentX = currentX,
            currentY = currentY,
            velocityX = velocityX,
            velocityY = velocityY,
            headingRadians = headingRadians,
            predictedX = currentX + velocityX * lookAheadFrames,
            predictedY = currentY + velocityY * lookAheadFrames,
            confidence = confidence
        )
    }

    fun current(): Prediction = latest
}
