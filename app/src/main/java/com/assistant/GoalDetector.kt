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
        val screenWidth = blobs.maxOf { it.maxX }.toFloat().coerceAtLeast(400f)
        val goalRegionLeft = screenWidth * GOAL_SCREEN_FRACTION

        // White blobs (goalposts/net) confined to the right goal region.
        // This prevents white jerseys anywhere on screen from corrupting the box.
        val whiteBlobs = blobs.filter {
            it.averageRed   > 220f &&
            it.averageGreen > 220f &&
            it.averageBlue  > 220f &&
            it.minX.toFloat() >= goalRegionLeft   // must start inside goal region
        }

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
/* ======
GoalDetector Anchor
====== */

/* ========
GoalOverlay
======== */
