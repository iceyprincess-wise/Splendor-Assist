package com.assistant.adapter.smartassist

object BallTrajectoryPredictor {

    data class Prediction(
        val currentX:Float=0f,
        val currentY:Float=0f,
        val velocityX:Float=0f,
        val velocityY:Float=0f,
        val predictedX:Float=0f,
        val predictedY:Float=0f,
        val speed:Float=0f
    )

    private var latest=Prediction()

    fun update(
        currentX:Float,
        currentY:Float,
        velocityX:Float,
        velocityY:Float,
        predictedX:Float,
        predictedY:Float,
        speed:Float
    ){
        latest=Prediction(
            currentX,
            currentY,
            velocityX,
            velocityY,
            predictedX,
            predictedY,
            speed
        )
    }

    fun current():Prediction=latest
}
