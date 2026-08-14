package com.assistant.adapter.smartassist

/**
 * PHASE4 UPGRADE: GoalkeeperDetector
 *
 * Previous state: single-frame detection, no smoothing, no coast.
 * At 15fps: one missed detection = 66ms gap. Without coast, keeper position
 * returns (0,0) on missed frames → KeeperFeedbackContributor fires to (0,0)
 * → every beast-save gesture targets the wrong position.
 *
 * Fixes:
 *  - 3-frame coast (200ms): last known keeper position held across 3 missed frames
 *    with confidence decay. Beast saves continue firing at correct position.
 *  - EWA smoothing: new detection weighted 0.65 over old 0.35 (responsive at 15fps)
 *  - MIN_CONFIDENCE = 0.18: reject weak jersey matches, reduce false positives
 */
object GoalkeeperDetector {

    private const val MIN_PIXEL_COUNT = 12
    private const val COAST_FRAMES = 3      // 15fps: 3 frames = 200ms coast
    private const val MIN_CONFIDENCE = 0.18f

    // Smoothed state — persists across frames
    @Volatile private var initialized = false
    @Volatile private var lastX = 0f
    @Volatile private var lastY = 0f
    @Volatile private var lastConf = 0f
    @Volatile private var lostFrames = 0

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalkeeperDetectionResult {

        var bestBlob: ConnectedComponentEngine.Blob? = null
        var bestScore = 0f

        for (blob in blobs) {
            if (blob.pixelCount < MIN_PIXEL_COUNT) continue

            val jersey = JerseyColorSegmentation.classify(
                blob.averageRed,
                blob.averageGreen,
                blob.averageBlue
            )
            if (jersey.team != JerseyColorSegmentation.Team.GOALKEEPER) continue

            // Score: jersey confidence weighted with blob size
            val sizeScore = (blob.pixelCount / 200f).coerceIn(0f, 1f)
            val composite = jersey.confidence * 0.65f + sizeScore * 0.35f

            if (composite > bestScore) {
                bestScore = composite
                bestBlob = blob
            }
        }

        if (bestBlob == null || bestScore < MIN_CONFIDENCE) {
            lostFrames++
            return if (initialized && lostFrames <= COAST_FRAMES) {
                // Coast: hold last known position with linearly decayed confidence
                // At 15fps: 3 frames = 200ms — beast save contributors keep firing
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                GoalkeeperDetectionResult(
                    detected = true,
                    x = lastX,
                    y = lastY,
                    confidence = (lastConf * coastFraction).coerceIn(0f, 1f)
                )
            } else {
                // Coast expired — lose the keeper
                initialized = false
                lastX = 0f; lastY = 0f; lastConf = 0f
                GoalkeeperDetectionResult(detected = false, x = 0f, y = 0f, confidence = 0f)
            }
        }

        lostFrames = 0
        val rawX = (bestBlob.minX + bestBlob.maxX) * 0.5f
        val rawY = (bestBlob.minY + bestBlob.maxY) * 0.5f

        // EWA smoothing: at 15fps keeper moves more between frames → new position wins
        val smoothX = if (initialized) lastX * 0.35f + rawX * 0.65f else rawX
        val smoothY = if (initialized) lastY * 0.35f + rawY * 0.65f else rawY
        val smoothConf = if (initialized) lastConf * 0.40f + bestScore * 0.60f else bestScore

        initialized = true
        lastX = smoothX
        lastY = smoothY
        lastConf = smoothConf

        return GoalkeeperDetectionResult(
            detected = true,
            x = smoothX,
            y = smoothY,
            confidence = smoothConf.coerceIn(0f, 1f)
        )
    }
}
