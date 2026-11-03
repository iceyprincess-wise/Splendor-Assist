package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.*

/** Pixel motion and distance; referenceSpeed is a model scale, not a game speed stat. */
data class AgilityObservation(val carrierId: Int, val x: Float, val y: Float,
    val opponentX: Float, val opponentY: Float, val speed: Float, val distance: Float,
    val headingDegrees: Float, val turnIntensity: Float, val confidence: Float,
    val referenceSpeed: Float)

fun observeAgility(frame: RuntimeFrame): AgilityObservation? {
    if (!frame.trusted || !frame.hasBall || !frame.captureWidth.isFinite() ||
        !frame.captureHeight.isFinite() || frame.captureWidth <= 0 || frame.captureHeight <= 0) return null
    fun valid(p: com.assistant.runtime.RuntimeEntity) = p.x.isFinite() && p.y.isFinite() &&
        p.confidence.isFinite() && p.confidence > 0f
    val carrier = frame.entities.firstOrNull { it.id == frame.ownerId && it.teammate && valid(it) } ?: return null
    if (!carrier.velocityXPerSecond.isFinite() || !carrier.velocityYPerSecond.isFinite()) return null
    val opponent = frame.entities.filter { !it.teammate && valid(it) }
        .minByOrNull { hypot(it.x-carrier.x,it.y-carrier.y) } ?: return null
    val speed = hypot(carrier.velocityXPerSecond,carrier.velocityYPerSecond)
    val distance = hypot(opponent.x-carrier.x,opponent.y-carrier.y)
    val reference = hypot(frame.captureWidth,frame.captureHeight)
    if (!speed.isFinite() || !distance.isFinite() || !reference.isFinite()) return null
    val heading = atan2(carrier.velocityYPerSecond,carrier.velocityXPerSecond)
    val desired = if (frame.passTargetId != null && frame.passTargetX.isFinite() && frame.passTargetY.isFinite())
        atan2(frame.passTargetY-carrier.y,frame.passTargetX-carrier.x) else heading
    val turn = if (speed > 0f) abs(atan2(sin(desired-heading),cos(desired-heading))) / PI.toFloat() else 0f
    return AgilityObservation(carrier.id,carrier.x,carrier.y,opponent.x,opponent.y,speed,distance,
        Math.toDegrees(desired.toDouble()).toFloat(),turn,
        minOf(frame.confidence,carrier.confidence,opponent.confidence).coerceIn(0f,1f),reference)
}
