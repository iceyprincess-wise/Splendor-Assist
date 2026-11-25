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
        // Item 15 (Letter B): the old deflection flag is gone.
        // It carried no information beyond valid=false on the same
        // frame, and no reader ever needed to distinguish "deflected"
        // from "unobserved" — both mean "don't trust this prediction".
        // MotionTracker's LOCAL deflection decision (zero the velocity,
        // re-anchor) is untouched.
        // Curve fix (R2/curved-shot keeper fix, 2026-11-24):
        // lateral acceleration, px/frame^2, averaged over two frames
        // by MotionTracker (see its field note). RAW value: not
        // clamped and not deadbanded here — every consumer must read
        // it through GoalBoundBallEngine.curvedXAt, the single choke
        // point for the deadband + clamp. 0 when the history is short,
        // on a deflection frame, or when the ball is not observed.
        // The linear predictedX/predictedY are untouched: no existing
        // reader's numbers move.
        val accelerationX:Float=0f,
        val accelerationY:Float=0f
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
        accelerationX:Float=0f,
        accelerationY:Float=0f
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
            accelerationX,
            accelerationY
        )
    }

    fun current():Prediction=latest
}

/* ========
BlockedLanePredictionEngine
======== */
