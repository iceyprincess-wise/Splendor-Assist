package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*


import kotlin.math.atan2

import kotlin.math.cos
import kotlin.math.hypot






import kotlin.math.sin















object TrueTargetPassingEngine {
    private const val TARGET_FPS = 60f // honest named fallback; no captured interval is exposed
    // No68-A-D13 / Q67-6 (KEPT OPEN, device proof): this 60 fps
    // assumption disagrees with AutoEvadeEngine (30 fps: predictionFrames
    // = 2f) and with the current game rate (20 fps per the FPS answer —
    // game 20 + app 60 is being abandoned for game 60 + app 60). At 20
    // fps this engine over-leads 3x. The constant is NOT silently
    // retuned here: the target rate (60) is what this constant already
    // assumes, and the measured processed rate is still unproven. The
    // same unmeasured rate also moves AdaptiveLoftedThroughEngine's
    // 27-frame look-ahead (No68-A-D1).

    /**
     * @param retention 0..1 fraction along (start->end) to land on.
     * BUG FIX: retention now clamped. Old callers passed * 10f -> 10x overshoot.
     */
    fun optimize(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        retention: Float
    ): PassingAssistResult {
        val r = retention.coerceIn(0f, 1f)
        val dx = endX - startX
        val dy = endY - startY
        return PassingAssistResult(
            correctedX       = (startX + dx * r).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            correctedY       = (startY + dy * r).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            interceptionRisk = (1f - r).coerceIn(0f, 1f)
        )
    }

    // No68-A-D13: DELETED as PROVEN dead (full-tree grep, zero callers
    // in app, core, or tests): optimizeWithRunPrediction (subsumed by
    // optimizeWithAdvancedTactics, which the only caller uses; it also
    // always reported interceptionRisk 0 — see PassingAssistResult),
    // interceptionVector, and calculateDoublePressEscapeVector (orphaned
    // helpers for an escape path that was never built — no consumer,
    // and no non-speculative wiring exists; cf. Q67-9's deletion).
    // Per the queue-clearing rule, PROVEN totally dead and confirmed
    // not wireable is REMOVED with proof, not left to rot.

    /**
     * Advanced Run & Match-Up Interception Escape Optimization.
     *
     * No68-A-D13: the old defender offset was defender-position-blind:
     * whenever `defenderDensity > 0.5` it shifted the target 25 px to
     * the same rotational side (clockwise of the pass direction) with no
     * knowledge of where the defender actually stands — so when the
     * defender stood on the clockwise side, the "escape" moved the
     * target TOWARD them. The offset now takes the nearest defender's
     * real position (plumbed by TruePassContributor, which has the
     * scene) and always offsets to the side AWAY from them: the side is
     * the sign of the cross product of the pass direction with
     * (defender − ball). `null` defender = no defender known = no
     * offset (the old arbitrary side is not preserved). Magnitude
     * (25 px) and trigger (density > 0.5) are unchanged.
     *
     * No68-A-D4: `interceptionRisk` here is `defenderDensity * 0.7` —
     * a different meaning from `optimize()`'s `1 - retention` and
     * `optimizeWithRunPrediction()`'s constant 0 (deleted as dead,
     * see below). Do not compare risks across producers.
     */
    fun optimizeWithAdvancedTactics(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float,
        defenderDensity: Float,
        nearestDefenderX: Float? = null,
        nearestDefenderY: Float? = null
    ): PassingAssistResult {
        val dist = hypot((receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = TARGET_FPS // no captured interval available in this scope

        var predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        var predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())

        // Match-Up / Defender Shadow Offset — away from the real defender.
        if (defenderDensity > 0.5f && nearestDefenderX != null && nearestDefenderY != null) {
            val passAngle = atan2((predY - ballY).toDouble(), (predX - ballX).toDouble())
            val dirX = cos(passAngle)
            val dirY = sin(passAngle)
            // Cross product of the pass direction with (defender − ball):
            // > 0, defender is counter-clockwise of the line → offset
            // clockwise; < 0 → offset counter-clockwise. On the line
            // (== 0) either side is equally clear; take clockwise.
            val cross = dirX * (nearestDefenderY - ballY) - dirY * (nearestDefenderX - ballX)
            val sign = if (cross < 0.0) -1f else 1f
            // Offset pass vector 25px orthogonal to defender density cone,
            // on the side away from the defender.
            predX += (sin(passAngle) * 25.0 * sign).toFloat()
            predY -= (cos(passAngle) * 25.0 * sign).toFloat()
        }

        val risk = (defenderDensity * 0.7f).coerceIn(0f, 1f)

        return PassingAssistResult(
            correctedX = predX.coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            correctedY = predY.coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            interceptionRisk = risk
        )
    }

    fun currentPassingGraph()                        = Phase3WorldStateStore.current().passingGraph
    fun currentThroughBallAnalysis()                 = Phase3WorldStateStore.current().throughBallAnalysis
    fun currentCrossingLaneAnalysis()                = Phase3WorldStateStore.current().crossingLaneAnalysis
    fun currentShootingLaneAnalysis()                = Phase3WorldStateStore.current().shootingLaneAnalysis
    fun currentBlockedLanePredictionAnalysis()       = Phase3WorldStateStore.current().blockedLanePredictionAnalysis
    fun currentDefenderInterceptionPredictionAnalysis() = Phase3WorldStateStore.current().defenderInterceptionPredictionAnalysis
    fun currentOpenSpaceDetectionResult()            = Phase3WorldStateStore.current().openSpaceDetectionResult
    fun currentReceiverRankingResult()               = Phase3WorldStateStore.current().receiverRankingResult
    fun currentRunPredictionResult()                 = Phase3WorldStateStore.current().runPredictionResult
    fun currentOverlapDetectionResult()              = Phase3WorldStateStore.current().overlapDetectionResult
    fun currentCounterattackDetectionResult()        = Phase3WorldStateStore.current().counterattackDetectionResult
    fun currentFastBreakDetectionResult()            = Phase3WorldStateStore.current().fastBreakDetectionResult
    fun currentOffsideRiskEstimationResult()         = Phase3WorldStateStore.current().offsideRiskEstimationResult
}

/* ========
VisionConfiguration
======== */
