package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * ControlledRingReader — direct pixel reader for the controlled-player
 * ring (the circle under the player Ed is controlling), in BOTH of
 * its measured states. Replaces the retired saturated-blue ring
 * branch in PlayerDetector.detect (one ring sensor, never two).
 *
 * MEASURED (two independent video sources, 2026-11-25):
 * - 2024 (BZG b4aQ8jX22mE): the ring is bright cyan/turquoise,
 *   GREEN-dominant — R 90-116, G 213-224, B 144-164. Clearest frame
 *   7:57: 836 cyan px, bbox 107x44, centre (336,562); also 7:39
 *   (100x30) and 8:04 (77x26-27). Aspect at the 640x288 capture
 *   scale ~0.30-0.40. A small cyan downward triangle sits above the
 *   controlled player (tip (1174,486) RGB (10,239,244)).
 * - 2027 (RIYANZ FC 8JeOdG47u0c): CONFIRMS the cyan state AND
 *   reveals the second state. In possession (Dumfries 4:06): bbox
 *   117x45, centre (697,488), on-ring R 17-83, G 207-234,
 *   B 153-196 — slightly deeper cyan than 2024. Far player (Eto'o
 *   5:18): >=88x22, sample (4,237,189). OFF the ball (2:03, 5:26):
 *   PALE white-green ellipse, 91x41 and 101x42, R 161-211,
 *   G 209-247, B 137-193 — a different game state, not a change to
 *   the cyan ring.
 * - ED'S CORRECTION (2026-11-25, from direct play + his
 *   screenshot): the switch is the DASH button — white/grey when NOT
 *   dashing, blue (light blue) when Dash is pressed and the player
 *   runs with speed. The footage is consistent with it (cyan frames
 *   are players with the ball, likely sprinting; pale frames are
 *   off-ball/defending, likely not dashing). Dash is the trigger.
 *
 * WHY THE OLD RULE FAILED (proven, twice over, on 26 measured
 * ring samples):
 * - Colour: the old rule demanded the blob classify OPPONENT via the
 *   saturated-blue rule (blue>140, blue>red+50, blue>green+50). The
 *   26 samples classify UNKNOWN x23 and GOALKEEPER x3 — OPPONENT x0.
 *   Blue-minus-green on the ring is -98..-27; the rule needs +50.
 *   It can never pass.
 * - Aspect: the old rule demanded aspect >= 0.60. Real rings
 *   measure 0.25-0.45. Density was never the failing constraint
 *   (0.18 < 0.40).
 * - The blob path is therefore dead for the real ring, and the fix
 *   is a direct pixel read, not a retuned blob rule.
 *
 * ALGORITHM (knob-reader style — whole-frame scan at step 2,
 * ~46k reads/frame at 640x288): each row's matched pixels (cyan or
 * pale) are reduced to a span; rows spanning >= 14 px with >= 3
 * matches are "counting" rows; counting rows form bands (gaps of
 * <= 2 non-counting rows tolerated). Each band is gated on width
 * 28-60 px, height 8-22 px, aspect 0.15-0.50, density 0.08-0.50,
 * >= 30 matched samples, and match fraction >= 0.40 against the
 * expected ring-stroke samples (Ramanujan circumference x 2.5 px
 * stroke, at step 2). The band's majority state (cyan/pale, >= 70%)
 * is the ring state; a mixed band is rejected. The best band (most
 * matched) wins; at most one ring per frame (the game shows exactly
 * one controlled player).
 * - The cyan triangle above the player is NOT the ring: its rows
 *   span ~3-7 px, below the 14 px counting span, so it can never
 *   form a band on its own. (It may be added later as a cross-check
 *   marker; v1 does not use it.)
 * - Hoarding blue (G < 200), pitch green (G < 200), the white ball
 *   (G-R < 10), white lines/kits (G-R < 10) and the joystick knob
 *   grey (achromatic, G-R = 0) all fail both colour rules.
 * - Density cap is 0.50, not 0.40: far rings are denser (the fixed
 *   ~2 px stroke is a larger fraction of a small bbox — the 5:18
 *   far ring measures 0.41). A solid pale/cyan blob would still
 *   need the exact colour + aspect + size window; none is known.
 * - The per-pixel margins are thin in places (pale G-B >= 25 vs a
 *   measured 27; pale G-R >= 10 vs a measured 12) — the gates are
 *   statistical (count + density + aspect), never per-pixel-perfect,
 *   exactly like the knob reader's 40% gate.
 *
 * FAIL-OPEN: null = no ring known, never a guessed centre. The
 * caller (VisionCore) leaves the players untouched on null.
 * Pure, stateless, no side effects (one RING log line per read).
 */
object ControlledRingReader {

    enum class RingState { CYAN, PALE }

    data class RingRead(
        val centerX: Float,
        val centerY: Float,
        val state: RingState,
        val confidence: Float,
        val width: Float,
        val height: Float,
        val aspect: Float,
        val density: Float,
        val matchedCount: Int
    )

    // CYAN state (dash / in possession): R 17-116, G 207-237, B 144-196.
    private const val CYAN_GREEN_MIN = 200
    private const val CYAN_GREEN_MINUS_RED = 90
    private const val CYAN_GREEN_MINUS_BLUE = 10
    private const val CYAN_BLUE_MIN = 140

    // PALE state (not dashing / off-ball): R 161-230, G 209-250, B 137-203.
    private const val PALE_RED_MIN = 160
    private const val PALE_GREEN_MIN = 205
    private const val PALE_BLUE_MIN = 135
    private const val PALE_GREEN_MINUS_RED = 10
    private const val PALE_GREEN_MINUS_BLUE = 25

    // Geometry (capture px; aspect is scale-invariant).
    private const val RING_MIN_WIDTH_PX = 28f
    private const val RING_MAX_WIDTH_PX = 60f
    private const val RING_MIN_HEIGHT_PX = 8f
    private const val RING_MAX_HEIGHT_PX = 22f
    private const val RING_MIN_ASPECT = 0.15f
    private const val RING_MAX_ASPECT = 0.50f
    private const val RING_MIN_DENSITY = 0.08f
    private const val RING_MAX_DENSITY = 0.50f
    private const val MIN_MATCH_SAMPLES = 30
    private const val MIN_MATCH_FRACTION = 0.4f
    private const val RING_STROKE_PX = 2.5f

    /** Same fixed confidence the retired ring branch emitted. */
    const val RING_CONFIDENCE = 0.55f

    private const val SCAN_STEP_PX = 2
    private const val MIN_ROW_SPAN_PX = 14
    private const val MIN_ROW_MATCH = 3
    private const val MAX_ROW_GAP = 2

    private fun px(b: ByteBuffer, idx: Int): Int = b.get(idx).toInt() and 0xFF

    private fun isCyan(r: Int, g: Int, bl: Int): Boolean =
        g >= CYAN_GREEN_MIN && g - r >= CYAN_GREEN_MINUS_RED &&
            g - bl >= CYAN_GREEN_MINUS_BLUE && bl >= CYAN_BLUE_MIN

    private fun isPale(r: Int, g: Int, bl: Int): Boolean =
        r >= PALE_RED_MIN && g >= PALE_GREEN_MIN && bl >= PALE_BLUE_MIN &&
            g - r >= PALE_GREEN_MINUS_RED && g - bl >= PALE_GREEN_MINUS_BLUE

    /**
     * Reads the controlled-player ring from the live frame. Returns
     * null when no ring can be read with confidence (no ring in
     * view, occluded, unsegmentable) — never a guess.
     */
    fun read(
        b: ByteBuffer,
        w: Int,
        h: Int,
        stride: Int,
        pixelStride: Int
    ): RingRead? {
        if (w <= 0 || h <= 0 || stride <= 0 || pixelStride < 3) return null
        val rowCount = (h + SCAN_STEP_PX - 1) / SCAN_STEP_PX
        if (rowCount <= 0) return null
        val rowMinX = IntArray(rowCount) { Int.MAX_VALUE }
        val rowMaxX = IntArray(rowCount) { Int.MIN_VALUE }
        val rowCyan = IntArray(rowCount)
        val rowPale = IntArray(rowCount)
        val rowCounting = BooleanArray(rowCount)
        var ri = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val idx = y.toLong() * stride + x.toLong() * pixelStride
                if (idx >= 0 && idx + 2 < b.limit()) {
                    val off = idx.toInt()
                    val r = px(b, off)
                    val g = px(b, off + 1)
                    val bl = px(b, off + 2)
                    if (isCyan(r, g, bl)) {
                        rowCyan[ri]++
                        if (x < rowMinX[ri]) rowMinX[ri] = x
                        if (x > rowMaxX[ri]) rowMaxX[ri] = x
                    } else if (isPale(r, g, bl)) {
                        rowPale[ri]++
                        if (x < rowMinX[ri]) rowMinX[ri] = x
                        if (x > rowMaxX[ri]) rowMaxX[ri] = x
                    }
                }
                x += SCAN_STEP_PX
            }
            val span = if (rowMinX[ri] == Int.MAX_VALUE) 0 else rowMaxX[ri] - rowMinX[ri] + 1
            rowCounting[ri] = span >= MIN_ROW_SPAN_PX && rowCyan[ri] + rowPale[ri] >= MIN_ROW_MATCH
            ri++
            y += SCAN_STEP_PX
        }
        // Group counting rows into bands (gaps of <= MAX_ROW_GAP
        // non-counting rows tolerated); evaluate each band.
        var best: RingRead? = null
        var bandFirst = -1
        var bandLast = -1
        var gap = 0
        fun flushBand() {
            if (bandFirst < 0) return
            evaluateBand(bandFirst, bandLast, rowMinX, rowMaxX, rowCyan, rowPale, rowCounting)?.let { cand ->
                if (best == null || cand.matchedCount > best!!.matchedCount) best = cand
            }
            bandFirst = -1
            bandLast = -1
            gap = 0
        }
        for (i in 0 until rowCount) {
            if (rowCounting[i]) {
                if (bandFirst < 0) bandFirst = i
                bandLast = i
                gap = 0
            } else if (bandFirst >= 0) {
                gap++
                if (gap > MAX_ROW_GAP) flushBand()
            }
        }
        flushBand()
        best?.let {
            try {
                RuntimeLogger.log(
                    "RING controlled=true state=${it.state} density=${it.density} aspect=${it.aspect} conf=$RING_CONFIDENCE center=(${it.centerX},${it.centerY})",
                    "PLAYER_DETECTOR"
                )
            } catch (_: Throwable) { }
        }
        return best
    }

    private fun evaluateBand(
        first: Int,
        last: Int,
        rowMinX: IntArray,
        rowMaxX: IntArray,
        rowCyan: IntArray,
        rowPale: IntArray,
        rowCounting: BooleanArray
    ): RingRead? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var cyan = 0
        var pale = 0
        for (i in first..last) {
            if (!rowCounting[i]) continue
            if (rowMinX[i] < minX) minX = rowMinX[i]
            if (rowMaxX[i] > maxX) maxX = rowMaxX[i]
            cyan += rowCyan[i]
            pale += rowPale[i]
        }
        if (minX == Int.MAX_VALUE) return null
        val width = (maxX - minX + 1).toFloat()
        val minY = first * SCAN_STEP_PX
        val maxY = last * SCAN_STEP_PX
        val height = (maxY - minY + 1).toFloat()
        if (width < RING_MIN_WIDTH_PX || width > RING_MAX_WIDTH_PX) return null
        if (height < RING_MIN_HEIGHT_PX || height > RING_MAX_HEIGHT_PX) return null
        val aspect = height / width
        if (aspect < RING_MIN_ASPECT || aspect > RING_MAX_ASPECT) return null
        val matched = cyan + pale
        if (matched < MIN_MATCH_SAMPLES) return null
        val stepArea = (SCAN_STEP_PX * SCAN_STEP_PX).toFloat()
        val density = (matched.toFloat() / (width * height / stepArea)).coerceIn(0f, 1f)
        if (density < RING_MIN_DENSITY || density > RING_MAX_DENSITY) return null
        // Expected ring-stroke samples at the scan step (Ramanujan
        // circumference x stroke, divided by the step area).
        val a = width / 2f
        val bb = height / 2f
        val circumference = PI.toFloat() * (3f * (a + bb) - sqrt((3f * a + bb) * (a + 3f * bb)))
        val expected = circumference * RING_STROKE_PX / stepArea
        if (expected <= 0f) return null
        val confidence = (matched.toFloat() / expected).coerceIn(0f, 1f)
        if (confidence < MIN_MATCH_FRACTION) return null
        val total = matched.toFloat()
        val state = when {
            cyan >= 0.7f * total -> RingState.CYAN
            pale >= 0.7f * total -> RingState.PALE
            else -> return null
        }
        return RingRead(
            centerX = (minX + maxX) * 0.5f,
            centerY = (minY + maxY) * 0.5f,
            state = state,
            confidence = confidence,
            width = width,
            height = height,
            aspect = aspect,
            density = density,
            matchedCount = matched
        )
    }

    /**
     * Merges a ring read into the player detections, replicating the
     * NMS hand-off the retired branch relied on: if a USER detection
     * already sits within the merge radius of the ring centre, it
     * IS the controlled player — set its flag and emit nothing new.
     * Otherwise append exactly one USER detection at the ring centre
     * with the flag set. A null ring leaves the result untouched
     * (fail-open). The merge radius is PlayerDetector's NMS radius
     * (captureHeight * 0.025, clamped 3..12).
     */
    fun mergeInto(
        result: PlayerDetectionResult,
        ring: RingRead?,
        captureHeight: Float
    ): PlayerDetectionResult {
        if (ring == null) return result
        val radius = (captureHeight * 0.025f).coerceIn(3f, 12f)
        val nearest = result.detections
            .filter { it.isUserTeam }
            .minByOrNull { (it.x - ring.centerX) * (it.x - ring.centerX) + (it.y - ring.centerY) * (it.y - ring.centerY) }
        if (nearest != null) {
            val dx = nearest.x - ring.centerX
            val dy = nearest.y - ring.centerY
            if (dx * dx + dy * dy < radius * radius) {
                nearest.isControlled = true
                return result
            }
        }
        return result.copy(
            detections = result.detections + PlayerDetection(
                ring.centerX, ring.centerY, RING_CONFIDENCE, true, true
            ),
            detected = true
        )
    }
}
