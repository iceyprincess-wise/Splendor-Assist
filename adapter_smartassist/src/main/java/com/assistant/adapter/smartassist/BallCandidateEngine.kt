package com.assistant.adapter.smartassist

import kotlin.math.abs
import kotlin.math.sqrt

object BallCandidateEngine {

    /*
     * Ball candidate ranking upgrade.
     *
     * This layer deliberately uses only evidence already present in Blob:
     * geometry, pixel count and average RGB values.
     *
     * It does not fabricate temporal, motion or frame-position evidence.
     * Those remain downstream responsibilities until their actual inputs
     * are audited.
     */

    private const val MIN_PIXELS = 3
    private const val MAX_PIXELS = 4096

    private const val EXPECTED_AREA = 200f

    private const val SIZE_WEIGHT = 0.25f
    private const val ASPECT_WEIGHT = 0.25f
    private const val DENSITY_WEIGHT = 0.20f
    private const val BRIGHTNESS_WEIGHT = 0.15f
    private const val NEUTRALITY_WEIGHT = 0.15f

    fun select(
        blobs: List<ConnectedComponentEngine.Blob>
    ): BallCandidate? {

        if (blobs.isEmpty()) return null

        var best: BallCandidate? = null

        for (blob in blobs) {

            val width =
                (blob.maxX - blob.minX + 1).toFloat()

            val height =
                (blob.maxY - blob.minY + 1).toFloat()

            val pixels = blob.pixelCount

            if (width <= 0f || height <= 0f) continue
            if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) continue

            val boundingArea = width * height

            if (boundingArea <= 0f) continue

            /*
             * Aspect ratio:
             * 1.0 = square
             * approaches 0 = very elongated
             */
            val aspect =
                if (width > height) {
                    height / width
                } else {
                    width / height
                }

            val aspectScore =
                aspect.coerceIn(0f, 1f)

            /*
             * Fill density:
             * how much of the bounding box is occupied by the component.
             *
             * Compact objects generally have higher occupancy than thin,
             * fragmented or highly elongated components.
             */
            val density =
                (pixels / boundingArea)
                    .coerceIn(0f, 1f)

            /*
             * Prefer a plausible ball-sized component rather than simply
             * rewarding larger blobs.
             *
             * sqrt() compresses the effect of area differences.
             */
            val normalizedSize =
                sqrt(pixels / EXPECTED_AREA)
                    .coerceIn(0f, 1.5f)

            val sizeScore =
                when {
                    normalizedSize <= 1f ->
                        normalizedSize

                    else ->
                        (2f - normalizedSize)
                            .coerceAtLeast(0f)
                }.coerceIn(0f, 1f)

            val red =
                blob.averageRed.coerceIn(0f, 255f)

            val green =
                blob.averageGreen.coerceIn(0f, 255f)

            val blue =
                blob.averageBlue.coerceIn(0f, 255f)

            val brightness =
                ((red + green + blue) / (255f * 3f))
                    .coerceIn(0f, 1f)

            /*
             * Neutrality measures how close RGB channels are to one another.
             *
             * A white/grey object approaches 1.
             * Strongly saturated colours approach 0.
             *
             * This is only a weak appearance feature; it is intentionally
             * not treated as proof that a component is the ball.
             */
            val maxChannel =
                maxOf(red, green, blue)

            val minChannel =
                minOf(red, green, blue)

            val neutrality =
                if (maxChannel <= 0f) {
                    0f
                } else {
                    (1f - ((maxChannel - minChannel) / maxChannel))
                        .coerceIn(0f, 1f)
                }

            /*
             * Reject components that are simultaneously very elongated and
             * extremely sparse. These are poor ball candidates regardless of
             * brightness.
             */
            if (aspectScore < 0.20f && density < 0.35f) continue

            val score =
                (
                    sizeScore * SIZE_WEIGHT +
                    aspectScore * ASPECT_WEIGHT +
                    density * DENSITY_WEIGHT +
                    brightness * BRIGHTNESS_WEIGHT +
                    neutrality * NEUTRALITY_WEIGHT
                ).coerceIn(0f, 1f)

            val candidate =
                BallCandidate(
                    centerX = (blob.minX + blob.maxX) * 0.5f,
                    centerY = (blob.minY + blob.maxY) * 0.5f,
                    radius = sqrt(pixels / Math.PI).toFloat(),
                    pixelCount = pixels,
                    brightness = brightness,
                    score = score
                )

            if (
                best == null ||
                candidate.score > best.score ||
                (
                    candidate.score == best.score &&
                    candidate.pixelCount < best.pixelCount
                )
            ) {
                best = candidate
            }
        }

        return best
    }
}
