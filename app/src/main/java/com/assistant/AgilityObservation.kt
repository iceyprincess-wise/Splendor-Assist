package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.*

// No58-D1 (V4-readiness): the pitch is a broadcast camera view, not a
// top-down map, so a pixel step in Y covers more pitch than the same step
// in X (foreshortening). The observation corrects the Y delta by this
// stretch BEFORE computing opponentDistance, so the distance the native
// engine sees is in roughly isotropic pitch units. DEFAULT is a DOCUMENTED
// DEFAULT, not a measured calibration: the midpoint of the 1.45-1.75 range
// in the V4 checklist. No homography or perspective factor exists anywhere
// in the repo (verified: no homography/perspective code in app, core or
// cpp; CameraProfile.captureToScreenX/Y is a linear scale only), so there
// is no measured value to use yet — replace this default with a measured
// per-camera value when one exists. Callers may pass their own stretch;
// an invalid value (non-finite or <= 0) falls back to 1 (no correction).
const val DEFAULT_PERSPECTIVE_STRETCH_Y = 1.6f

// No58-D2: threat look-ahead horizon for defender selection, in seconds.
// threat = distance - closingSpeed * THREAT_LOOKAHEAD_SEC; closingSpeed is
// px/s, so the product is px and the score stays in px (lower = more urgent).
const val THREAT_LOOKAHEAD_SEC = 0.25f

/** Pixel motion and distance; referenceSpeed is a model scale, not a game speed stat. */
data class AgilityObservation(val carrierId: Int, val x: Float, val y: Float,
    val opponentX: Float, val opponentY: Float, val speed: Float, val distance: Float,
    val directionDegrees: Float, val turnIntensity: Float, val confidence: Float,
    val referenceSpeed: Float,
    val oppVelocityXPerSecond: Float, val oppVelocityYPerSecond: Float,
    val closingSpeed: Float)

fun observeAgility(frame: RuntimeFrame,
    perspectiveStretchY: Float = DEFAULT_PERSPECTIVE_STRETCH_Y): AgilityObservation? {
    if (!frame.trusted || !frame.hasBall || !frame.captureWidth.isFinite() ||
        !frame.captureHeight.isFinite() || frame.captureWidth <= 0 || frame.captureHeight <= 0) return null
    fun valid(p: com.assistant.runtime.RuntimeEntity) = p.x.isFinite() && p.y.isFinite() &&
        p.confidence.isFinite() && p.confidence > 0f
    val carrier = frame.entities.firstOrNull { it.id == frame.ownerId && it.teammate && valid(it) } ?: return null
    if (!carrier.velocityXPerSecond.isFinite() || !carrier.velocityYPerSecond.isFinite()) return null
    // No58-D3: THREAT selection, not nearest. Every detected defender is
    // scored by its time-to-intercept proxy (threat = perspective-corrected
    // distance - closingSpeed * THREAT_LOOKAHEAD_SEC) and the LOWEST score
    // wins. A farther defender sprinting at the carrier outranks a nearer
    // one drifting away. closingSpeed is the raw-geometry closing rate
    // (positive when the gap shrinks); opponent velocities are real — the
    // frame's predictor projection fills velocityXPerSecond /
    // velocityYPerSecond for every entity (FrameAssembler), so no
    // frame-to-frame tracker is needed here. frame.trusted already
    // guarantees every entity velocity is finite (RuntimeFrame.trusted).
    // Duplicate ownerIds resolve first-wins (deterministic; a duplicate id
    // is an upstream data error, not something this function can repair).
    val stretch = if (perspectiveStretchY.isFinite() && perspectiveStretchY > 0f) perspectiveStretchY else 1f
    fun closingOf(opponent: com.assistant.runtime.RuntimeEntity): Float {
        val dx = opponent.x - carrier.x
        val dy = opponent.y - carrier.y
        val rawDist = hypot(dx, dy)
        if (rawDist <= 0f) return 0f
        val relVx = opponent.velocityXPerSecond - carrier.velocityXPerSecond
        val relVy = opponent.velocityYPerSecond - carrier.velocityYPerSecond
        return -((relVx * dx + relVy * dy) / rawDist)
    }
    fun threatOf(opponent: com.assistant.runtime.RuntimeEntity): Float {
        val dx = opponent.x - carrier.x
        val dy = (opponent.y - carrier.y) * stretch
        val distance = hypot(dx, dy)
        return distance - closingOf(opponent) * THREAT_LOOKAHEAD_SEC
    }
    val opponent = frame.entities.filter { !it.teammate && valid(it) }
        .minByOrNull { threatOf(it) } ?: return null
    val speed = hypot(carrier.velocityXPerSecond, carrier.velocityYPerSecond)
    val distance = hypot(opponent.x - carrier.x, (opponent.y - carrier.y) * stretch)
    val closingSpeed = closingOf(opponent)
    val reference = hypot(frame.captureWidth, frame.captureHeight)
    if (!speed.isFinite() || !distance.isFinite() || !reference.isFinite()) return null
    val heading = atan2(carrier.velocityYPerSecond, carrier.velocityXPerSecond)
    val desired = if (frame.passTargetId != null && frame.passTargetX.isFinite() && frame.passTargetY.isFinite())
        atan2(frame.passTargetY - carrier.y, frame.passTargetX - carrier.x) else heading
    // No58-D4: the field is named directionDegrees, not headingDegrees: it
    // holds the DESIRED direction (toward the pass target) when a pass
    // target is set, and the velocity heading otherwise. The old name lied
    // on the pass-target branch; the value is unchanged. A stationary
    // carrier (speed == 0) has no heading, so turn is 0 even with a pass
    // target — intended, not a missing branch.
    val turn = if (speed > 0f) abs(atan2(sin(desired - heading), cos(desired - heading))) / PI.toFloat() else 0f
    // No58-D5: confidence is the weakest link (min of frame, carrier and
    // opponent), coerced to 0..1. Entity confidences are not capped at 1 by
    // valid(), so the coerce is load-bearing when one exceeds 1.
    return AgilityObservation(carrier.id, carrier.x, carrier.y, opponent.x, opponent.y, speed, distance,
        Math.toDegrees(desired.toDouble()).toFloat(), turn,
        minOf(frame.confidence, carrier.confidence, opponent.confidence).coerceIn(0f, 1f), reference,
        opponent.velocityXPerSecond, opponent.velocityYPerSecond, closingSpeed)
}
