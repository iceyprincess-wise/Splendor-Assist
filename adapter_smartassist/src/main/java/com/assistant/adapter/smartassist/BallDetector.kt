package com.assistant.adapter.smartassist

object BallDetector {

    private const val MIN_CANDIDATE_CONFIDENCE = 0.12f
    private const val COAST_FRAMES = 6

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
            }
            return BallDetectionResult(
                detected = false,
                x = 0f,
                y = 0f,
                radius = 0f,
                confidence = 0f,
                searchPixels = 0,
                matchedPixels = 0
            )
        }

        successfulFrames++
        lostFrames = 0

        val filteredX =
            if (initialized)
                lastBallX * 0.55f + candidate.centerX * 0.45f
            else
                candidate.centerX

        val filteredY =
            if (initialized)
                lastBallY * 0.55f + candidate.centerY * 0.45f
            else
                candidate.centerY

        val filteredRadius =
            if (initialized)
                lastRadius * 0.60f + candidate.radius * 0.40f
            else
                candidate.radius

        val filteredConfidence =
            if (initialized)
                lastConfidence * 0.50f + candidate.score * 0.50f
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
