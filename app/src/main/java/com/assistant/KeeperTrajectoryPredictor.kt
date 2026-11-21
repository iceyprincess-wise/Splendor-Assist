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

    // Item 12 (C2/K): the look-ahead horizon, now the single source of
    // truth — SceneTracker used to hardcode 6f at its two feed sites.
    const val LOOK_AHEAD_FRAMES = 6f

    // Item 12 (C5): a prediction older than this is stale — without an
    // expiry the last valid prediction was trusted indefinitely when
    // vision stalled. 150 ms is 3 frames at the 20 fps processed rate
    // (stated tuning).
    const val PREDICTION_MAX_AGE_MS = 150L

    data class Prediction(
        // px per FRAME — same unit as BallTrajectoryPredictor, so the
        // look-ahead multiplies by frames, never by a guessed fps.
        val velocityX: Float = 0f,
        val velocityY: Float = 0f,
        val predictedX: Float = 0f,
        val predictedY: Float = 0f,
        // False whenever the keeper was not seen on the feeding frame
        // (or never seen): the prediction is stale and consumers must
        // fall back to the current position.
        val valid: Boolean = false,
        // Item 12 (C5): when this prediction was written, so
        // interceptPosition() can expire it when vision stalls.
        val updatedAtMs: Long = 0L
    )

    private var latest = Prediction()

    fun update(
        velocityX: Float,
        velocityY: Float,
        predictedX: Float,
        predictedY: Float,
        valid: Boolean = false,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        latest = Prediction(
            velocityX,
            velocityY,
            predictedX,
            predictedY,
            valid,
            updatedAtMs
        )
    }

    fun current(): Prediction = latest

    /**
     * The position a save-path consumer should measure from: the
     * predicted keeper position when the prediction is fresh, the
     * caller's current keeper position otherwise. Callers never branch
     * on validity themselves, and the fallback is exactly today's
     * behaviour (measure from where the keeper is).
     */
    fun interceptPosition(currentX: Float, currentY: Float): Pair<Float, Float> {
        val p = latest
        // Item 12 (C5): expire a prediction the feeder has not refreshed
        // — a stalled feed must fall back to the current position, not
        // trust a point the keeper left frames ago.
        val fresh = p.valid &&
            p.updatedAtMs > 0L &&
            System.currentTimeMillis() - p.updatedAtMs <= PREDICTION_MAX_AGE_MS
        return if (fresh) p.predictedX to p.predictedY else currentX to currentY
    }
}

/* ========
BallTrajectoryPredictor
======== */
