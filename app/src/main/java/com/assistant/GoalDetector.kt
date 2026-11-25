package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object GoalDetector {

    // OMEGA FIX: Dynamic Wide Camera Scaling — removed with the strip
    // rule (item 12 C8): the goal box now comes from all white
    // candidates, not the outer 30% strips.
    private const val MIN_GOAL_PIXELS = 15

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalDetectionResult {

        if (blobs.isEmpty()) {
            return GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f, source = GoalDetectionSource.BLOB)
        }

        // Estimate screen width from the widest blob span seen this frame.
        val screenWidth = com.assistant.vision.CameraProfile.captureWidthOrFallback()

        // Item 12 (C8/C9): the old strip rule only accepted goals jammed
        // into the outer 30% strips (maxX < 0.30W or minX >= 0.70W) — a
        // normal centred goal (posts ~0.32W/0.68W) matched neither side
        // and read not-detected (581/605 in v220), and a single detected
        // post read as a few-px-wide box ("width-too-narrow"). Take the
        // box from ALL white candidates and require at least two blobs
        // so one post alone cannot pose as a goal. The calibrator's
        // width/frame checks downstream still bound the box (a stray
        // white jersey widening it past 60% reads invalid, not
        // fabricated).
        val candidates = blobs.filter {
            it.averageRed   > 220f &&
            it.averageGreen > 220f &&
            it.averageBlue  > 220f
        }

        val whiteBlobs = candidates
        if (whiteBlobs.size < 2) {
            return GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f, source = GoalDetectionSource.BLOB)
        }

        val left   = whiteBlobs.minOf { it.minX }.toFloat()
        val right  = whiteBlobs.maxOf { it.maxX }.toFloat()
        val top    = whiteBlobs.minOf { it.minY }.toFloat()
        val bottom = whiteBlobs.maxOf { it.maxY }.toFloat()
        val pixels = whiteBlobs.sumOf { it.pixelCount }

        return GoalDetectionResult(
            detected   = pixels >= MIN_GOAL_PIXELS && right > left &&
                right - left >= screenWidth * 0.02f,
            leftX      = left,
            rightX     = right,
            topY       = top,
            bottomY    = bottom,
            confidence = (pixels / 300f).coerceIn(0f, 1f),
            source     = GoalDetectionSource.BLOB
        )
    }
}

/* ========
GoalOverlay
======== */
