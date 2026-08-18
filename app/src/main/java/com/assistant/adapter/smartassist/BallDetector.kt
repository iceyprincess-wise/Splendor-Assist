package com.assistant.adapter.smartassist

object BallDetector {

    private const val MIN_CANDIDATE_CONFIDENCE = 0.12f
    private const val COAST_FRAMES = 4  // PHASE4: 15fps — 6 frames=400ms coast too long; 4=266ms

    private var lastBallX = 0f
    private var lastBallY = 0f
    private var lastRadius = 0f
    private var lastConfidence = 0f

    private var initialized = false
    private var lostFrames = 0
    private var totalFrames = 0
    private var successfulFrames = 0

    fun detect(
        candidate: BallCandidate?
    ): BallDetectionResult {

        totalFrames++

        if (candidate == null || candidate.score < MIN_CANDIDATE_CONFIDENCE) {
            lostFrames++
            if (lostFrames > COAST_FRAMES) {
                initialized = false
                lastBallX = 0f
                lastBallY = 0f
                lastRadius = 0f
                lastConfidence = 0f
                return BallDetectionResult(
                    detected = false,
                    x = 0f, y = 0f, radius = 0f, confidence = 0f,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            if (initialized) {
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                val coastConfidence = (lastConfidence * coastFraction).coerceIn(0f, 1f)
                return BallDetectionResult(
                    detected = true,
                    x = lastBallX, y = lastBallY, radius = lastRadius,
                    confidence = coastConfidence,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            return BallDetectionResult(
                detected = false,
                x = 0f, y = 0f, radius = 0f, confidence = 0f,
                searchPixels = 0, matchedPixels = 0
            )
        }

        successfulFrames++
        lostFrames = 0

        // PHASE4: 15fps EWA — ball moves more between frames, new position must dominate
        // Was 0.55(old)/0.45(new) → lagged badly at 15fps
        // Now 0.40(old)/0.60(new) → tracks fast movement, still smooths noise
        val filteredX =
            if (initialized)
                lastBallX * 0.40f + candidate.centerX * 0.60f
            else
                candidate.centerX

        val filteredY =
            if (initialized)
                lastBallY * 0.40f + candidate.centerY * 0.60f
            else
                candidate.centerY

        val filteredRadius =
            if (initialized)
                lastRadius * 0.45f + candidate.radius * 0.55f
            else
                candidate.radius

        val filteredConfidence =
            if (initialized)
                lastConfidence * 0.40f + candidate.score * 0.60f
            else
                candidate.score

        initialized = true

        lastBallX = filteredX
        lastBallY = filteredY
        lastRadius = filteredRadius
        lastConfidence = filteredConfidence

        val pixelArea = (candidate.pixelCount).coerceAtLeast(1)

        return BallDetectionResult(
            detected = true,
            x = filteredX,
            y = filteredY,
            radius = filteredRadius,
            confidence = filteredConfidence.coerceIn(0f, 1f),
            searchPixels = pixelArea,
            matchedPixels = (pixelArea * filteredConfidence).toInt().coerceAtLeast(1)
        )
    }
}
