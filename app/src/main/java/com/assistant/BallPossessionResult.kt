package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BallPossessionResult(
    val hasPossession: Boolean,
    val ownerIndex: Int = -1,
    val possessionFrames: Long = 0L,
    val possessionChanged: Boolean = false,
    val confidence: Float = 0f
)



/* ========
BallTelemetryBridge
======== */
/*
 * The missing wire between detection and trust.
 *
 * BallDetector produced a BallDetectionResult every frame, but nothing ever
 * pushed it into TelemetryRepository -- so FrameAssembler always saw
 * ballX/ballY == 0, hasBall stayed false, the frame was never trusted, and
 * only DefenseContributor could act.
 *
 * This bridge is the single writer of ball telemetry. It publishes ONLY on a
 * real detection, and derives velocity from its own previous position so it
 * needs no other engine's internals.
 *
 * Task B repair: every real detection now ALSO stamps VisionTrust - the
 * trust gate reads an age-decayed ball sighting, and starving it meant the
 * trusted flag barely ever opened even mid-match. Stamping at the single
 * writer guarantees the trust chain is fed exactly when the telemetry is.
 */
