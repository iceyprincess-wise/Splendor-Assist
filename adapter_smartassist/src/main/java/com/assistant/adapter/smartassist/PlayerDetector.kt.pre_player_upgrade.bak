package com.assistant.adapter.smartassist

object PlayerDetector {

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): PlayerDetectionResult {

        val detections = blobs.map {
            val centerX = (it.minX + it.maxX) * 0.5f
            val centerY = (it.minY + it.maxY) * 0.5f
            val confidence = (it.pixelCount / 64f).coerceIn(0f,1f)

            val jersey =
                JerseyColorSegmentation.classify(
                    it.averageRed,
                    it.averageGreen,
                    it.averageBlue
                )

            PlayerDetection(
                x = centerX,
                y = centerY,
                confidence = confidence,
                isUserTeam =
                    jersey.team ==
                    JerseyColorSegmentation.Team.USER
            )
        }

        return PlayerDetectionResult(
            detected = detections.isNotEmpty(),
            playerCount = detections.size,
            confidence = if(detections.isEmpty()) 0f else 1f,
            detections = detections
        )
    }
}
