package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























// Batch-trajectory (B): the keeper predictor. The deleted
// GoalkeeperTrajectoryPredictor (6d) was a 6-frame look-ahead store
// with zero callers; this is a NEW file built from the trace, not a
// resurrection of it.
//
// Feed: SceneTracker.update() publishes the measured keeper track
// (trackedGoalkeeper velocityX/Y, px per FRAME) into this predictor
// every frame the keeper is seen. Same 6-frame look-ahead maths as
// BallTrajectoryPredictor (~300 ms at the 20 fps processed rate):
// the feed is px-per-FRAME velocity, so no fps is guessed and no
// sixty-fps constant appears anywhere here.
//
// Consumer: OmnipotentGoalkeeperEngine.processGoalkeeperDefensiveLayer
// measures the 1v1 rush from the intercept position (predicted keeper
// when fresh, current keeper otherwise) — saves aim at where the
// keeper WILL be, not where he is. The router (GoalkeeperActionRouter
// .route) takes only a ThreatDecision and no keeper position, so it is
// NOT a consumer; the defensive layer is the one place on the keeper
// path where the keeper's position changes a decision.
object KeeperTrajectoryPredictor {

    private const val LOOK_AHEAD_FRAMES = 6f

    data class Prediction(
        val currentX: Float = 0f,
        val currentY: Float = 0f,
        // px per FRAME — same unit as BallTrajectoryPredictor, so the
        // look-ahead multiplies by frames, never by a guessed fps.
        val velocityX: Float = 0f,
        val velocityY: Float = 0f,
        val predictedX: Float = 0f,
        val predictedY: Float = 0f,
        val speed: Float = 0f,
        // False whenever the keeper was not seen on the feeding frame
        // (or never seen): the prediction is stale and consumers must
        // fall back to the current position.
        val valid: Boolean = false
    )

    private var latest = Prediction()

    fun update(
        currentX: Float,
        currentY: Float,
        velocityX: Float,
        velocityY: Float,
        predictedX: Float,
        predictedY: Float,
        speed: Float,
        valid: Boolean = false
    ) {
        latest = Prediction(
            currentX,
            currentY,
            velocityX,
            velocityY,
            predictedX,
            predictedY,
            speed,
            valid
        )
    }

    fun current(): Prediction = latest

    /**
     * Test hook: resets the prediction to the never-seen state. No
     * production caller uses it.
     */
    fun reset() {
        latest = Prediction()
    }

    /**
     * The position a save-path consumer should measure from: the
     * predicted keeper position when the prediction is fresh, the
     * caller's current keeper position otherwise. Callers never branch
     * on validity themselves, and the fallback is exactly today's
     * behaviour (measure from where the keeper is).
     */
    fun interceptPosition(currentX: Float, currentY: Float): Pair<Float, Float> {
        val p = latest
        return if (p.valid) p.predictedX to p.predictedY else currentX to currentY
    }
}

/* ========
BallTrajectoryPredictor
======== */
