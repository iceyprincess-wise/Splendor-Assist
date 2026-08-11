package com.assistant.adapter.smartassist

object PlayerDetector {

    private const val MIN_PIXEL_COUNT = 3
    private const val MIN_ASPECT_RATIO = 0.15f
    private const val MIN_CANDIDATE_CONFIDENCE = 0.30f

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): PlayerDetectionResult {
        if (blobs.isEmpty()) {
            return PlayerDetectionResult(
                detected = false,
                playerCount = 0,
                confidence = 0f,
                detections = emptyList()
            )
        }

        val detections = ArrayList<PlayerDetection>(blobs.size)

        for (blob in blobs) {
            if (blob.pixelCount < MIN_PIXEL_COUNT) {
                continue
            }

            val width = (blob.maxX - blob.minX + 1).coerceAtLeast(1)
            val height = (blob.maxY - blob.minY + 1).coerceAtLeast(1)

            val area = width.toFloat() * height.toFloat()
            val density =
                (blob.pixelCount.toFloat() / area).coerceIn(0f, 1f)

            val shortSide = minOf(width, height).toFloat()
            val longSide = maxOf(width, height).toFloat()

            val aspectRatio =
                if (longSide > 0f) {
                    shortSide / longSide
                } else {
                    0f
                }

            if (aspectRatio < MIN_ASPECT_RATIO) {
                continue
            }

            val centerX = (blob.minX + blob.maxX) * 0.5f
            val centerY = (blob.minY + blob.maxY) * 0.5f

            val jersey =
                JerseyColorSegmentation.classify(
                    blob.averageRed,
                    blob.averageGreen,
                    blob.averageBlue
                )

            val sizeScore =
                (blob.pixelCount / 64f).coerceIn(0f, 1f)

            val densityScore =
                density.coerceIn(0f, 1f)

            val shapeScore =
                ((aspectRatio - MIN_ASPECT_RATIO) /
                    (1f - MIN_ASPECT_RATIO))
                    .coerceIn(0f, 1f)

            val jerseyScore =
                jersey.confidence.coerceIn(0f, 1f)

            val confidence =
                (
                    sizeScore * 0.30f +
                    densityScore * 0.25f +
                    shapeScore * 0.20f +
                    jerseyScore * 0.25f
                ).coerceIn(0f, 1f)

            if (confidence < MIN_CANDIDATE_CONFIDENCE) {
                continue
            }

            detections.add(
                PlayerDetection(
                    x = centerX,
                    y = centerY,
                    confidence = confidence,
                    isUserTeam =
                        jersey.team ==
                            JerseyColorSegmentation.Team.USER
                )
            )
        }

        detections.sortByDescending { it.confidence }

        val aggregateConfidence =
            if (detections.isEmpty()) {
                0f
            } else {
                detections
                    .map { it.confidence }
                    .average()
                    .toFloat()
                    .coerceIn(0f, 1f)
            }

        return PlayerDetectionResult(
            detected = detections.isNotEmpty(),
            playerCount = detections.size,
            confidence = aggregateConfidence,
            detections = detections
        )
    }
}
