package com.assistant.adapter.smartassist

import kotlin.math.abs

object PlayerDetector {

    private const val MIN_PIXEL_COUNT = 10
    private const val MIN_ASPECT_RATIO = 0.15f
    private const val MIN_CANDIDATE_CONFIDENCE = 0.30f
    private const val NMS_OVERLAP_THRESHOLD = 0.40f

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

        val raw = ArrayList<PlayerDetection>(blobs.size)

        for (blob in blobs) {
            if (blob.pixelCount < MIN_PIXEL_COUNT) continue

            val width = (blob.maxX - blob.minX + 1).coerceAtLeast(1)
            val height = (blob.maxY - blob.minY + 1).coerceAtLeast(1)
            val area = width.toFloat() * height.toFloat()
            val density = (blob.pixelCount.toFloat() / area).coerceIn(0f, 1f)
            val shortSide = minOf(width, height).toFloat()
            val longSide = maxOf(width, height).toFloat()
            val aspectRatio = if (longSide > 0f) shortSide / longSide else 0f

            if (aspectRatio < MIN_ASPECT_RATIO) continue

            val centerX = (blob.minX + blob.maxX) * 0.5f
            val centerY = (blob.minY + blob.maxY) * 0.5f

            val jersey = JerseyColorSegmentation.classify(
                blob.averageRed,
                blob.averageGreen,
                blob.averageBlue
            )

            val sizeScore = (blob.pixelCount / 64f).coerceIn(0f, 1f)
            val densityScore = density.coerceIn(0f, 1f)
            val shapeScore = ((aspectRatio - MIN_ASPECT_RATIO) /
                (1f - MIN_ASPECT_RATIO)).coerceIn(0f, 1f)
            val jerseyScore = jersey.confidence.coerceIn(0f, 1f)

            val confidence = (
                sizeScore * 0.30f +
                densityScore * 0.25f +
                shapeScore * 0.20f +
                jerseyScore * 0.25f
            ).coerceIn(0f, 1f)

            if (confidence < MIN_CANDIDATE_CONFIDENCE) continue

            raw.add(
                PlayerDetection(
                    x = centerX,
                    y = centerY,
                    confidence = confidence,
                    isUserTeam = jersey.team == JerseyColorSegmentation.Team.USER
                )
            )
        }

        raw.sortByDescending { it.confidence }

        // NMS: suppress lower-confidence detection if it overlaps a better one
        val kept = ArrayList<PlayerDetection>(raw.size)
        val suppressed = BooleanArray(raw.size)
        for (i in raw.indices) {
            if (suppressed[i]) continue
            kept.add(raw[i])
            for (j in i + 1 until raw.size) {
                if (suppressed[j]) continue
                val dx = abs(raw[i].x - raw[j].x)
                val dy = abs(raw[i].y - raw[j].y)
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                // suppress j if within ~40px of a better detection
                if (dist < 40f) suppressed[j] = true
            }
        }

        val aggregateConfidence = if (kept.isEmpty()) 0f else
            kept.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)

        return PlayerDetectionResult(
            detected = kept.isNotEmpty(),
            playerCount = kept.size,
            confidence = aggregateConfidence,
            detections = kept
        )
    }
}
