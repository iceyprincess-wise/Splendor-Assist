package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object BallTrajectoryPredictor {

    data class Prediction(
        val currentX:Float=0f,
        val currentY:Float=0f,
        val velocityX:Float=0f,
        val velocityY:Float=0f,
        val predictedX:Float=0f,
        val predictedY:Float=0f,
        val speed:Float=0f,
        // Batch-trajectory (A): validity. False on the frame a bounce /
        // deflection is detected (the displacement straddles the impact,
        // so the velocity mixes pre- and post-bounce motion) and whenever
        // the ball is not observed. Readers that ignore it see the same
        // conservative values as before (velocity / speed zeroed on the
        // invalid frame, predicted = current). Readers that check it know
        // when the prediction is fresh vs stale.
        val valid:Boolean=false,
        // Batch-trajectory (A): true only on the frame the deflection
        // was detected. The next frame re-anchors from the new velocity.
        val deflectionDetected:Boolean=false
    )

    private var latest=Prediction()

    fun update(
        currentX:Float,
        currentY:Float,
        velocityX:Float,
        velocityY:Float,
        predictedX:Float,
        predictedY:Float,
        speed:Float,
        valid:Boolean=false,
        deflectionDetected:Boolean=false
    ){
        latest=Prediction(
            currentX,
            currentY,
            velocityX,
            velocityY,
            predictedX,
            predictedY,
            speed,
            valid,
            deflectionDetected
        )
    }

    fun current():Prediction=latest
}

/* ========
BlockedLanePredictionEngine
======== */
