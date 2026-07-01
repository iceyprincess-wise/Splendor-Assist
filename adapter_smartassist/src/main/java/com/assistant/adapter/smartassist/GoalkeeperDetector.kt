package com.assistant.adapter.smartassist

object GoalkeeperDetector {

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalkeeperDetectionResult {

        val keeperBlob =
            blobs
                .map { blob ->
                    blob to JerseyColorSegmentation.classify(
                        blob.averageRed,
                        blob.averageGreen,
                        blob.averageBlue
                    )
                }
                .filter {
                    it.second.team ==
                    JerseyColorSegmentation.Team.GOALKEEPER
                }
                .maxByOrNull {
                    it.first.pixelCount
                }

        if (keeperBlob == null) {
            return GoalkeeperDetectionResult(
                detected = false,
                x = 0f,
                y = 0f,
                confidence = 0f
            )
        }

        val blob = keeperBlob.first

        return GoalkeeperDetectionResult(
            detected = true,
            x = (blob.minX + blob.maxX) * 0.5f,
            y = (blob.minY + blob.maxY) * 0.5f,
            confidence =
                (blob.pixelCount / 150f)
                    .coerceIn(0f,1f)
        )
    }
}
