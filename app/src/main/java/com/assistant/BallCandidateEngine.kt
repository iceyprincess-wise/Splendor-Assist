package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*
import kotlin.math.PI












import kotlin.math.sqrt














object BallCandidateEngine {

    /*
     * Ball candidate ranking upgrade.
     *
     * This layer deliberately uses only evidence already present in Blob:
     * geometry, pixel count and average RGB values — plus ONE audited
     * temporal input (batch 6b): the last ball position BallDetector
     * already holds. The temporal prior is a bounded bonus, never a
     * fabrication: a blob near where the ball was scores higher, but a
     * far blob with a clearly better appearance score still wins.
     * No motion or frame-position evidence beyond that is used here.
     */

    // Batch 6b (measured 2026-11-20 from real eFootball frames in
    // ~/workspace/efootball_frames/hunt/): at match distance the ball
    // is ~4-5 px diameter (~12-20 px^2) at the 288x640 capture — e.g.
    // the white ball at Mbappe's feet is ~11 px across in a 720-wide
    // phone recording (x0.4 -> ~4.4 px at capture), and ~4-6 px across
    // in 640-wide YouTube frames. Contact 27's coloured panels split
    // the white base into same-kind fragments (ConnectedComponentEngine
    // grows blobs only through same-kind pixels), so the white core can
    // be 3-4 px. MIN_PIXELS=5 could drop a real distant ball; 3 keeps
    // the 3-4 px white core while NoiseFilter (min 4 px) still removes
    // sub-4 px noise upstream. 1-2 px blobs stay rejected here.
    private const val MIN_PIXELS = 3
    private const val MAX_PIXELS = 4096

    private const val EXPECTED_AREA = 200f

    // Batch 6b temporal prior: at the 20 fps processed rate a ball
    // cannot teleport — a blob within this radius of the last ball
    // position earns a bonus worth at most 0.15 of score, fading
    // linearly to 0 at the radius. The bonus can break a tie against
    // white-structure competition (goalposts, line fragments, white
    // kit stripes) but can never outvote a clearly better candidate:
    // appearance still owns 0.85 of the score.
    private const val TEMPORAL_RADIUS_PX = 60f
    private const val TEMPORAL_BONUS = 0.15f

    private const val SIZE_WEIGHT = 0.25f
    private const val ASPECT_WEIGHT = 0.25f
    private const val DENSITY_WEIGHT = 0.20f
    private const val BRIGHTNESS_WEIGHT = 0.15f
    private const val NEUTRALITY_WEIGHT = 0.15f

    fun select(
        blobs: List<ConnectedComponentEngine.Blob>,
        // Batch 6b: last known ball position from BallDetector, or null
        // when the detector has never locked on. Null = no prior.
        lastBall: Pair<Float, Float>? = null
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
                    neutrality * NEUTRALITY_WEIGHT +
                    temporalBonus(
                        (blob.minX + blob.maxX) * 0.5f,
                        (blob.minY + blob.maxY) * 0.5f,
                        lastBall
                    )
                ).coerceIn(0f, 1f)

            val candidate =
                BallCandidate(
                    centerX = (blob.minX + blob.maxX) * 0.5f,
                    centerY = (blob.minY + blob.maxY) * 0.5f,
                    radius = sqrt(pixels / Math.PI).toFloat(),
                    pixelCount = pixels,
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

    /*
     * Batch 6b temporal prior (pure, pinned): 0 when there is no last
     * position or the blob is outside TEMPORAL_RADIUS_PX of it, rising
     * linearly to TEMPORAL_BONUS at the last position. Non-finite
     * inputs earn 0 — a corrupt prior must never boost a blob.
     */
    fun temporalBonus(
        centerX: Float,
        centerY: Float,
        lastBall: Pair<Float, Float>?
    ): Float {
        if (lastBall == null) return 0f
        val dx = centerX - lastBall.first
        val dy = centerY - lastBall.second
        val dist = sqrt(dx * dx + dy * dy)
        if (!dist.isFinite()) return 0f
        if (dist >= TEMPORAL_RADIUS_PX) return 0f
        return (TEMPORAL_BONUS * (1f - dist / TEMPORAL_RADIUS_PX)).coerceIn(0f, TEMPORAL_BONUS)
    }
}

/* ========
BallDetectionResult
======== */
