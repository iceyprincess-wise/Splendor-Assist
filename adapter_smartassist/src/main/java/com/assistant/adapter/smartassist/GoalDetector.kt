package com.assistant.adapter.smartassist

object GoalDetector {

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalDetectionResult {

        if (blobs.isEmpty()) {
            return GoalDetectionResult(
                detected = false,
                leftX = 0f,
                rightX = 0f,
                topY = 0f,
                bottomY = 0f,
                confidence = 0f
            )
        }

        val whiteBlobs =
            blobs.filter {
                it.averageRed > 220f &&
                it.averageGreen > 220f &&
                it.averageBlue > 220f
            }

        if (whiteBlobs.isEmpty()) {
            return GoalDetectionResult(
                detected = false,
                leftX = 0f,
                rightX = 0f,
                topY = 0f,
                bottomY = 0f,
                confidence = 0f
            )
        }

        val left =
            whiteBlobs.minOf { it.minX }.toFloat()

        val right =
            whiteBlobs.maxOf { it.maxX }.toFloat()

        val top =
            whiteBlobs.minOf { it.minY }.toFloat()

        val bottom =
            whiteBlobs.maxOf { it.maxY }.toFloat()

        val pixels =
            whiteBlobs.sumOf { it.pixelCount }

        return GoalDetectionResult(
            detected = pixels >= 40,
            leftX = left,
            rightX = right,
            topY = top,
            bottomY = bottom,
            confidence = (pixels / 300f).coerceIn(0f,1f)
        )
    }
}
