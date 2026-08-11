package com.assistant.adapter.smartassist

object GoalkeeperDetector {

    private const val MIN_PIXEL_COUNT = 12

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

            // Score = jersey confidence weighted with blob size (capped so huge
            // blobs don't dominate over a well-classified smaller one)
            val sizeScore = (blob.pixelCount / 200f).coerceIn(0f, 1f)
            val composite = jersey.confidence * 0.65f + sizeScore * 0.35f

            if (composite > bestScore) {
                bestScore = composite
                bestBlob = blob
            }
        }

        if (bestBlob == null) {
            return GoalkeeperDetectionResult(
                detected = false,
                x = 0f,
                y = 0f,
                confidence = 0f
            )
        }

        return GoalkeeperDetectionResult(
            detected = true,
            x = (bestBlob.minX + bestBlob.maxX) * 0.5f,
            y = (bestBlob.minY + bestBlob.maxY) * 0.5f,
            confidence = bestScore.coerceIn(0f, 1f)
        )
    }
}
