package com.assistant




























import com.assistant.diagnostic.RuntimeLogger









import com.assistant.runtime.*


























import kotlin.math.*

import kotlin.math.abs


























object PlayerDetector {

    private const val MIN_PIXEL_COUNT = 10
    private const val MIN_ASPECT_RATIO = 0.15f
    private const val MIN_CANDIDATE_CONFIDENCE = 0.30f
    
    private val rawPool = Array(1000) { PlayerDetection(0f, 0f, 0f, false) }
    private val keptPool = Array(1000) { PlayerDetection(0f, 0f, 0f, false) }
    private val suppressed = BooleanArray(1000)
    private val resultList = object : java.util.AbstractList<PlayerDetection>() {
        var count = 0
        override val size get() = count
        override fun get(index: Int): PlayerDetection {
            if (index < 0 || index >= count) {
                val trace = Thread.currentThread().stackTrace.joinToString("\n") { "  at $it" }
                try { com.assistant.diagnostic.RuntimeLogger.log("FATAL OOB: index=$index, count=$count\n$trace", "PLAYER_DETECTOR") } catch (_: Throwable) {}
                throw IndexOutOfBoundsException("length=${keptPool.size}; index=$index\nCaller:\n$trace")
            }
            return keptPool[index]
        }
        fun bind(c: Int) { count = c }
    }

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

        var rawCount = 0

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

            // OMEGA FIX: Dynamic Wide Camera Depth Scaling
            val captureHeight = com.assistant.vision.CameraProfile.captureHeightOrFallback()
            val depthFactor = if (centerY < captureHeight * 0.5f) 0.25f else 1.0f
            val effectivePixelCount = blob.pixelCount / depthFactor

            val jersey = JerseyColorSegmentation.classify(
                blob.averageRed,
                blob.averageGreen,
                blob.averageBlue
            )

            if (jersey.team != JerseyColorSegmentation.Team.USER && jersey.team != JerseyColorSegmentation.Team.OPPONENT) continue
            val sizeScore = (effectivePixelCount / 64f).coerceIn(0f, 1f)
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

            if (rawCount < rawPool.size) {
                val pd = rawPool[rawCount]
                pd.x = centerX
                pd.y = centerY
                pd.confidence = confidence
                pd.isUserTeam = jersey.team == JerseyColorSegmentation.Team.USER
                rawCount++
            }
        }

        java.util.Arrays.sort(rawPool, 0, rawCount, compareByDescending<PlayerDetection> { it.confidence })

        // NMS: suppress lower-confidence detection if it overlaps a better one
        var keptCount = 0
        var sumConf = 0f
        val safeRawCount = minOf(rawCount, rawPool.size)
        java.util.Arrays.fill(suppressed, 0, safeRawCount, false)
        for (i in 0 until rawCount) {
            if (suppressed[i]) continue
            val pi = rawPool[i]
            if (keptCount < keptPool.size) {
                val kd = keptPool[keptCount]
                kd.x = pi.x; kd.y = pi.y; kd.confidence = pi.confidence; kd.isUserTeam = pi.isUserTeam
                keptCount++
            }
            sumConf += pi.confidence
            for (j in i + 1 until rawCount) {
                if (suppressed[j]) continue
                val dx = abs(pi.x - rawPool[j].x)
                val dy = abs(pi.y - rawPool[j].y)
                val distSq = dx * dx + dy * dy
                val radius = (com.assistant.vision.CameraProfile.captureHeightOrFallback() * 0.025f).coerceIn(3f, 12f)
                if (pi.isUserTeam == rawPool[j].isUserTeam && distSq < radius * radius) suppressed[j] = true
            }
        }

        val aggregateConfidence = if (keptCount == 0) 0f else (sumConf / keptCount).coerceIn(0f, 1f)
        resultList.bind(keptCount)

        return PlayerDetectionResult(
            detected = keptCount > 0,
            playerCount = keptCount,
            confidence = aggregateConfidence,
            detections = (0 until keptCount).map { i -> keptPool[i].let { PlayerDetection(it.x, it.y, it.confidence, it.isUserTeam) } }
        )
    }
}

/* ========
RuntimeConfidenceCalibrationResult
======== */
