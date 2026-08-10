package com.assistant.adapter.smartassist

import kotlin.math.abs
import kotlin.math.sqrt

object BallCandidateEngine {

    fun select(
        blobs: List<ConnectedComponentEngine.Blob>
    ): BallCandidate? {

        var best: BallCandidate? = null

        for (blob in blobs) {

            val width =
                (blob.maxX - blob.minX + 1).toFloat()

            val height =
                (blob.maxY - blob.minY + 1).toFloat()

            if (width <= 0f || height <= 0f)
                continue

            val aspect =
                if (width > height)
                    height / width
                else
                    width / height

            val centerX =
                (blob.minX + blob.maxX) * 0.5f

            val centerY =
                (blob.minY + blob.maxY) * 0.5f

            val radius =
                sqrt(blob.pixelCount / Math.PI).toFloat()

            val brightness =
                (
                    blob.averageRed +
                    blob.averageGreen +
                    blob.averageBlue
                ) / (255f * 3f)

            val sizeScore =
                (blob.pixelCount / 200f)
                    .coerceIn(0f, 1f)

            val aspectScore =
                (1f - abs(1f - aspect))
                    .coerceIn(0f, 1f)

            val score =
                (
                    sizeScore * 0.40f +
                    aspectScore * 0.35f +
                    brightness * 0.25f
                ).coerceIn(0f, 1f)

            val candidate =
                BallCandidate(
                    centerX = centerX,
                    centerY = centerY,
                    radius = radius,
                    pixelCount = blob.pixelCount,
                    brightness = brightness,
                    score = score
                )

            if (best == null || candidate.score > best.score)
                best = candidate
        }

        return best
    }
}
