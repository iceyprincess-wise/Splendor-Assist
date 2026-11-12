package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object GoalDetector {

    // OMEGA FIX: Dynamic Wide Camera Scaling
    // Expanded search region to rightmost 70% (0.30f fraction).
    private const val GOAL_SCREEN_FRACTION = 0.30f
    private const val MIN_GOAL_PIXELS = 15

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalDetectionResult {

        if (blobs.isEmpty()) {
            return GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f)
        }

        // Estimate screen width from the widest blob span seen this frame.
        val screenWidth = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val goalRegionLeft = screenWidth * GOAL_SCREEN_FRACTION

        // White blobs (goalposts/net) confined to the right goal region.
        // This prevents white jerseys anywhere on screen from corrupting the box.
        val candidates = blobs.filter {
            it.averageRed   > 220f &&
            it.averageGreen > 220f &&
            it.averageBlue  > 220f &&
            (it.minX.toFloat() >= goalRegionLeft || it.maxX < screenWidth * GOAL_SCREEN_FRACTION)
        }

        val leftCandidates = candidates.filter { it.maxX < screenWidth * GOAL_SCREEN_FRACTION }
        val rightCandidates = candidates.filter { it.minX >= screenWidth * (1f - GOAL_SCREEN_FRACTION) }
        val whiteBlobs = if (leftCandidates.sumOf { it.pixelCount } > rightCandidates.sumOf { it.pixelCount }) leftCandidates else rightCandidates
        if (whiteBlobs.isEmpty()) {
            return GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f)
        }

        val left   = whiteBlobs.minOf { it.minX }.toFloat()
        val right  = whiteBlobs.maxOf { it.maxX }.toFloat()
        val top    = whiteBlobs.minOf { it.minY }.toFloat()
        val bottom = whiteBlobs.maxOf { it.maxY }.toFloat()
        val pixels = whiteBlobs.sumOf { it.pixelCount }

        return GoalDetectionResult(
            detected   = pixels >= MIN_GOAL_PIXELS && right > left,
            leftX      = left,
            rightX     = right,
            topY       = top,
            bottomY    = bottom,
            confidence = (pixels / 300f).coerceIn(0f, 1f)
        )
    }
}

/* ========
GoalOverlay
======== */
