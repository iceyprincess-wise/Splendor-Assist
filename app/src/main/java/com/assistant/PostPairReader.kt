package com.assistant

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PostPairReader — direct goal-post reader, v3 signature (Lead
 * Coordinator #47; rewrites #40's v1, which FAILED acceptance).
 *
 * Reads the two goal posts straight off the frame pixels, instead of
 * trusting the GoalDetector blob box (which takes its span from ALL
 * white blobs — kits, lines, posts, ball — so any white kit or line
 * widens it; v268: 12% of GOAL_POSTS calibrations read
 * width-too-wide, 41% not-detected).
 *
 * MEASURED SIGNATURE v3 (640x288 working space — NOT 288x640; the
 * "288x640" phrasing is height x width. Proven from v268 GOAL_POSTS
 * widths up to 383 px, truncated by the 60% bound at 384):
 * - Pixel rule: postlike = (min channel >= 195) OR (R >= 140 AND
 *   |R-G| <= 14 AND |G-B| <= 14).
 * - Bars: group all >= 20 px white column runs; join adjacent columns
 *   when overlap >= 50 px absolute AND >= 0.25 x min(segment, extent).
 * - Per-row, two-pass (replaces bounding-box width — a leaning post's
 *   box is wider than the post): centreline fit from per-row longest
 *   runs (third-medians) -> lean-following extension (walk up/down
 *   while a 3-13 px run sits within 6 px of the centreline) -> pass 2
 *   re-measures thickness as the run NEAREST the fitted centreline
 *   (median), plus coverage and median residual.
 * - STRONG (near/mid): median thickness in [10, 25] AND thickness
 *   IQR <= 6.0 AND height >= 60 AND slope <= 0.105 (6 deg) AND median
 *   residual <= 3 px AND coverage >= 0.80.
 * - FAR: median thickness in [1, 2] AND IQR <= 6.0 AND height >= 20
 *   AND slope <= 0.105 AND residual <= 3 AND coverage >= 0.80.
 * - Pair: STRONG pool first, then FAR pool. Span 25-520. Score =
 *   (both_tall, span, height_sum) where both_tall = 1 if both heights
 *   >= 150 else 0 — this prefers the true tall pair and stops a
 *   1-column tip bar from winning on span alone. conf 0.9 (STRONG) /
 *   0.65 (FAR).
 * - Anchor (v2.3): both bottoms >= h-5 (frame bottom) OR a single
 *   horizontal run covering the mouth (length >= 0.8 x span AND
 *   spanning [L.cx_bot, R.cx_bot]) within [base, base+15] whose FULL
 *   vertical extent (walk up/down while a run overlaps the mouth by
 *   >= 0.5 x span) is <= 5 rows. The extent test measures the whole
 *   structure (~28 rows for a menu text band) and cannot be fooled by
 *   a long run sitting at the band's bottom edge.
 *
 * WHAT v3 CORRECTED (measured with mirror ~/workspace/post_mirror_v2.py,
 * 2026-11-25): #37's 5-7 px "post width" measured the bright core, not
 * the run — the post's RUN length is 16-20 px (median 18). The net
 * gives SHORT column runs (~15 px) and does NOT form long bars; the
 * real problem is post fragmentation by lean + shadows + occlusion.
 * The real right post on v1_111 is at x585-594 (runs of 184-205 px
 * reaching the frame bottom), NOT x604-621 (#40's mirror was wrong —
 * the 94 px top-drift it measured traces the diagonal crossbar, not
 * the post's lean; the post itself leans ~3.7 deg).
 *
 * MIRROR PROOF (v2.3, all 8 frames): v1_111 PAIR[STRONG] span 513.0,
 * conf 0.9 — left x69-95, y23-279, h257, thick 18.0, iqr 4.0, slope
 * 0.090, resid 1.8, cov 0.93; right x579-612, y0-279, h280, thick
 * 19.0, iqr 5.0, slope 0.085, resid 0.7, cov 0.82; keeper-shirt run
 * (x326-338) NOT in the pair. v1_041 (celebration) NO PAIR. v1_048
 * (half-time menu) NO PAIR. v1_040 (far goal) NO PAIR, honest.
 * v1_065 / v1_115 / v1_098 / v1_108 NO PAIR on all four.
 *
 * HONEST LIMITS: FAR RANGE IS NOT PROVEN — the reader claims
 * near/mid only. The 2027 far-post samples read pale yellow-white
 * (|G-B| up to 119), contradicting the achromatic rule; no closer
 * far-goal frame has proved a far rule yet. Proven on the 8 disk
 * frames via the mirror, not on live frames — on-device proof is the
 * next pass (GOAL_POSTS valid rate up, width-too-wide share down from
 * 12%, with source=READER). It fails open (null) whenever no pair
 * qualifies — never a guessed post.
 *
 * This file is a line-for-line port of the proven mirror
 * (post_mirror_v2.py): same constants, same two-pass measurement,
 * same anchor, same pair scoring. Any divergence between this file
 * and the mirror is a defect — the harness checks the constants and
 * the mirror's frame outcomes, and the verifier reads the port.
 *
 * Cost: one whole-frame pass over the 640x288 working frame, same
 * order as the knob reader's window scan. No new whole-frame scan is
 * added by the caller — VisionCore already holds this frame's buffer.
 * On the CRITICAL-memory ball-only path the reader runs only in
 * BALL_GOAL mode, not on every ball-only frame.
 */
object PostPairReader {

    /** Near/mid post pixel: min channel >= 195 (measured post min 195). */
    const val WHITE_MIN = 195

    /** Far post pixel floor for the achromatic rule (knob reader's rule). */
    const val ACHRO_R_MIN = 140

    /** Achromatic tolerance — the trainer's isGray tolerance. */
    private const val ACHRO_TOL = 14

    /** Minimum continuous vertical run for a post column (px). */
    private const val MIN_RUN = 20

    /** Pair span bounds (px at 640x288). */
    private const val MIN_SPAN = 25f
    private const val MAX_SPAN = 520f

    /** Vertical within 6 degrees: slope <= tan(6) ~= 0.105. */
    private const val LEAN_MAX = 0.105f

    /** Median centreline residual bound (px). */
    private const val RESID_MAX = 3.0f

    /** Minimum row coverage of the extended extent. */
    private const val COVER_MIN = 0.80f

    /** Thickness IQR bound (px) — consistency kills the keeper kit. */
    private const val IQR_MAX = 6.0f

    /** STRONG (near/mid) median thickness band (px). */
    private const val THICK_NEAR_MIN = 10f
    private const val THICK_NEAR_MAX = 25f

    /** FAR median thickness band (px). */
    private const val THICK_FAR_MIN = 1f
    private const val THICK_FAR_MAX = 2f

    /** STRONG minimum height (px); FAR minimum height (px, mirror value). */
    private const val H_STRONG = 60
    private const val H_FAR = 20

    /** Both-tall preference: both bars >= 150 px tall. */
    private const val H_BOTH_TALL = 150

    /** Bar-join: minimum absolute overlap (px) between adjacent columns. */
    private const val JOIN_OVERLAP_MIN = 50

    /** Bar-join: overlap must also be >= this fraction of the shorter run. */
    private const val JOIN_OVERLAP_FRAC = 0.25f

    /** Lean-following extension: max rows beyond the raw extent. */
    private const val EXTEND_MAX = 120

    /** Lean-following extension: search window half-width around the centreline (px). */
    private const val EXTEND_WINDOW = 12

    /** Lean-following extension: run centre must sit within this of the centreline (px). */
    private const val EXTEND_NEAR = 6f

    /** Lean-following extension: accepted run thickness range (px). */
    private const val EXTEND_THICK_MIN = 3
    private const val EXTEND_THICK_MAX = 13

    /** Anchor: bar bottoms within this of the frame bottom count as reaching it (px). */
    private const val ANCHOR_BOTTOM_MARGIN = 5

    /** Goal-line anchor: search window above the base (rows). */
    private const val GOAL_LINE_WINDOW = 16

    /** Goal-line anchor: the mouth run's full vertical extent must be <= this (rows). */
    private const val GOAL_LINE_MAX_THICK = 5

    /** Goal-line anchor: mouth run must cover this fraction of the span. */
    private const val GOAL_LINE_COVER_FRAC = 0.8f

    /** Goal-line anchor: a row persists while a run overlaps the mouth by this fraction. */
    private const val GOAL_LINE_PERSIST_FRAC = 0.5f

    /** One measured post bar. top/bottom are the EXTENDED extent (after lean-following). */
    data class PostBar(
        val x0: Int,
        val x1: Int,
        val top: Int,
        val bottom: Int,
        /** Median per-row thickness (px) from pass 2 — the measured post width. */
        val thickness: Float,
        /** Fitted centreline at the top / bottom of the bar. */
        val cxTop: Float,
        val cxBot: Float
    ) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = bottom - top + 1
        val centreX: Float get() = (cxTop + cxBot) / 2f
    }

    data class PostPair(
        val left: PostBar,
        val right: PostBar,
        val confidence: Float
    ) {
        /** Span measured on the centreline bases, as the mirror scores it. */
        val span: Float get() = right.cxBot - left.cxBot
    }

    private fun px(b: ByteBuffer, idx: Int): Int = b.get(idx).toInt() and 0xFF

    private fun isPostPixel(b: ByteBuffer, off: Int): Boolean {
        val r = px(b, off)
        val g = px(b, off + 1)
        val bl = px(b, off + 2)
        if (min(min(r, g), bl) >= WHITE_MIN) return true
        return r >= ACHRO_R_MIN && abs(r - g) <= ACHRO_TOL && abs(g - bl) <= ACHRO_TOL
    }

    /** Median (numpy semantics: average of the two middles for even n). */
    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    /** Percentile with numpy linear interpolation; input must be sorted ascending. */
    private fun percentile(sorted: List<Float>, p: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100f * (sorted.size - 1)
        val lo = rank.toInt().coerceIn(0, sorted.size - 1)
        val hi = min(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    /** Vertical runs of post-like pixels in one column, length >= MIN_RUN. */
    private fun colRuns(like: BooleanArray, w: Int, h: Int, x: Int): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var y = 0
        while (y < h) {
            if (!like[y * w + x]) {
                y++
                continue
            }
            val top = y
            while (y < h && like[y * w + x]) y++
            if (y - top >= MIN_RUN) runs.add(top to y - 1)
        }
        return runs
    }

    /** Horizontal runs of post-like pixels in one row within [lo, hi] (inclusive). */
    private fun rowRuns(like: BooleanArray, w: Int, y: Int, lo: Int, hi: Int): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var x = lo
        while (x <= hi) {
            if (!like[y * w + x]) {
                x++
                continue
            }
            val t = x
            while (x <= hi && like[y * w + x]) x++
            runs.add(t to x - 1)
        }
        return runs
    }

    /** (centre, length) of every white run in row y within [lo, hi]. */
    private fun rowCentres(like: BooleanArray, w: Int, y: Int, lo: Int, hi: Int): List<Pair<Float, Int>> {
        val out = mutableListOf<Pair<Float, Int>>()
        for ((t, b) in rowRuns(like, w, y, lo, hi)) {
            out.add((t + (b - t) / 2f) to (b - t + 1))
        }
        return out
    }

    /** Centreline fit: third-medians of per-row centres (mirror _fit). */
    private fun fit(ys: List<Int>, centres: Map<Int, Float>): FloatArray {
        val n = ys.size
        val k = max(1, n / 3)
        val t3 = ys.subList(0, k)
        val b3 = ys.subList(n - k, n)
        val cTop = median(t3.map { centres.getValue(it) })
        val cBot = median(b3.map { centres.getValue(it) })
        val yTop = t3.sum().toFloat() / t3.size
        val yBot = b3.sum().toFloat() / b3.size
        return floatArrayOf(cTop, cBot, yTop, yBot)
    }

    /** Group columns into raw bars (mirror find_bars). */
    private fun findBars(like: BooleanArray, w: Int, h: Int): List<PostBar> {
        val colRuns = Array(w) { x -> colRuns(like, w, h, x) }
        val bars = mutableListOf<PostBar>()
        var x = 0
        while (x < w) {
            val segs = colRuns[x]
            if (segs.isEmpty()) {
                x++
                continue
            }
            var seg = segs.maxByOrNull { it.second - it.first + 1 }!!
            var top = seg.first
            var bot = seg.second
            var x1 = x
            while (x1 + 1 < w && colRuns[x1 + 1].isNotEmpty()) {
                var best: Pair<Int, Int>? = null
                var bestOv = 0
                for ((t, bo) in colRuns[x1 + 1]) {
                    val ov = min(bot, bo) - max(top, t) + 1
                    if (ov > bestOv) {
                        best = t to bo
                        bestOv = ov
                    }
                }
                if (best == null) break
                val extLen = bot - top + 1
                val bestLen = best.second - best.first + 1
                if (bestOv < JOIN_OVERLAP_MIN ||
                    bestOv < JOIN_OVERLAP_FRAC * min(bestLen, extLen)
                ) break
                x1++
                top = min(top, best.first)
                bot = max(bot, best.second)
            }
            // Raw bar; thickness/centreline are measured by analyseBar.
            bars.add(PostBar(x, x1, top, bot, 0f, 0f, 0f))
            x = x1 + 1
        }
        return bars
    }

    /** Two-pass measurement of one raw bar (mirror analyse_bar); null when unmeasurable. */
    private fun analyseBar(like: BooleanArray, w: Int, h: Int, bar: PostBar): MeasuredBar? {
        val x0 = bar.x0
        val x1 = bar.x1
        val top = bar.top
        val bot = bar.bottom
        val height = bot - top + 1
        val allow = max(4, kotlin.math.ceil(LEAN_MAX * height).toInt())
        // Pass 1: longest run per row -> centreline fit.
        val c1 = mutableMapOf<Int, Float>()
        for (y in top..bot) {
            val rr = rowCentres(like, w, y, max(0, x0 - allow), min(w - 1, x1 + allow))
            if (rr.isEmpty()) continue
            val (c, _) = rr.maxByOrNull { it.second }!!
            c1[y] = c
        }
        if (c1.isEmpty()) return null
        val ys1 = c1.keys.sorted()
        val f1 = fit(ys1, c1)
        val cTop = f1[0]
        val cBot = f1[1]
        val yTop = f1[2]
        val yBot = f1[3]
        fun centreAt(y: Int): Float {
            return if (yBot > yTop) cTop + (cBot - cTop) * (y - yTop) / (yBot - yTop) else cTop
        }
        // Lean-following extension along the centreline.
        var extTop = top
        var extBot = bot
        var y = top - 1
        while (y >= 0 && top - y <= EXTEND_MAX) {
            val cx = centreAt(y)
            val rr = rowCentres(
                like, w, y,
                (cx - EXTEND_WINDOW).toInt().coerceAtLeast(0),
                (cx + EXTEND_WINDOW).toInt().coerceAtMost(w - 1)
            )
            val near = rr.filter { abs(it.first - cx) <= EXTEND_NEAR && it.second in EXTEND_THICK_MIN..EXTEND_THICK_MAX }
            if (near.isEmpty()) break
            extTop = y
            y--
        }
        y = bot + 1
        while (y < h && y - bot <= EXTEND_MAX) {
            val cx = centreAt(y)
            val rr = rowCentres(
                like, w, y,
                (cx - EXTEND_WINDOW).toInt().coerceAtLeast(0),
                (cx + EXTEND_WINDOW).toInt().coerceAtMost(w - 1)
            )
            val near = rr.filter { abs(it.first - cx) <= EXTEND_NEAR && it.second in EXTEND_THICK_MIN..EXTEND_THICK_MAX }
            if (near.isEmpty()) break
            extBot = y
            y++
        }
        // Pass 2: run nearest the centreline, over the extended extent.
        val height2 = extBot - extTop + 1
        val allow2 = max(4, kotlin.math.ceil(LEAN_MAX * height2).toInt())
        val rows = mutableMapOf<Int, Pair<Float, Int>>()
        for (yy in extTop..extBot) {
            val rr = rowCentres(like, w, yy, max(0, x0 - allow2), min(w - 1, x1 + allow2))
            if (rr.isEmpty()) continue
            val (c, l) = rr.minByOrNull { abs(it.first - centreAt(yy)) }!!
            if (abs(c - centreAt(yy)) <= EXTEND_NEAR) rows[yy] = c to l
        }
        if (rows.isEmpty()) return null
        val ys = rows.keys.sorted()
        val cov = ys.size.toFloat() / height2
        val lens = ys.map { rows.getValue(it).second.toFloat() }
        val thick = median(lens)
        val sortedLens = lens.sorted()
        val iqr = percentile(sortedLens, 75f) - percentile(sortedLens, 25f)
        val f2 = fit(ys, ys.associateWith { rows.getValue(it).first })
        val cTop2 = f2[0]
        val cBot2 = f2[1]
        val yTop2 = f2[2]
        val yBot2 = f2[3]
        fun centreAt2(yy: Int): Float {
            return if (yBot2 > yTop2) cTop2 + (cBot2 - cTop2) * (yy - yTop2) / (yBot2 - yTop2) else cTop2
        }
        val slope = abs(cBot2 - cTop2) / max(1f, yBot2 - yTop2)
        val resid = if (yBot2 > yTop2) median(ys.map { abs(rows.getValue(it).first - centreAt2(it)) }) else 0f
        return MeasuredBar(PostBar(x0, x1, extTop, extBot, thick, cTop2, cBot2), slope, resid, cov, iqr)
    }

    // Measured metrics travel with the bar through classification; the
    // public PostBar stays a plain data class (stable for callers).
    private data class MeasuredBar(
        val bar: PostBar,
        val slope: Float,
        val resid: Float,
        val cov: Float,
        val iqr: Float
    )

    private fun isStrong(m: MeasuredBar): Boolean {
        return m.bar.thickness in THICK_NEAR_MIN..THICK_NEAR_MAX &&
            m.iqr <= IQR_MAX &&
            m.bar.height >= H_STRONG &&
            m.slope <= LEAN_MAX &&
            m.resid <= RESID_MAX &&
            m.cov >= COVER_MIN
    }

    private fun isFar(m: MeasuredBar): Boolean {
        return m.bar.thickness in THICK_FAR_MIN..THICK_FAR_MAX &&
            m.iqr <= IQR_MAX &&
            m.bar.height >= H_FAR &&
            m.slope <= LEAN_MAX &&
            m.resid <= RESID_MAX &&
            m.cov >= COVER_MIN
    }

    /** Goal-line anchor (mirror goal_line_ok). */
    private fun goalLineOk(like: BooleanArray, w: Int, h: Int, l: PostBar, r: PostBar): Boolean {
        val span = r.cxBot - l.cxBot
        if (span < MIN_SPAN) return false
        val base = max(l.bottom, r.bottom)
        fun persists(yy: Int): Boolean {
            for ((tt, bb) in rowRuns(like, w, yy, 0, w - 1)) {
                val ov = min(bb, r.cxBot.toInt()) - max(tt, l.cxBot.toInt()) + 1
                if (ov.toFloat() >= GOAL_LINE_PERSIST_FRAC * span) return true
            }
            return false
        }
        for (y in base until min(h, base + GOAL_LINE_WINDOW)) {
            for ((t, b) in rowRuns(like, w, y, 0, w - 1)) {
                if (t.toFloat() <= l.cxBot && b.toFloat() >= r.cxBot &&
                    (b - t + 1).toFloat() >= GOAL_LINE_COVER_FRAC * span
                ) {
                    var top = y
                    while (top - 1 >= 0 && persists(top - 1)) top--
                    var bot = y
                    while (bot + 1 < h && persists(bot + 1)) bot++
                    if (bot - top + 1 <= GOAL_LINE_MAX_THICK) return true
                }
            }
        }
        return false
    }

    /**
     * Reads the post pair from the frame. b is the RGB buffer in the
     * same layout JoystickKnobReader.read takes (rowStride /
     * pixelStride). Returns null when no pair qualifies — never a
     * guessed post.
     */
    fun read(
        b: ByteBuffer,
        w: Int,
        h: Int,
        stride: Int,
        pixelStride: Int
    ): PostPair? {
        if (w <= 0 || h <= 0 || pixelStride < 3) return null
        if (b.limit() < stride * (h - 1) + pixelStride * w) return null

        // 1. Post-like mask over the whole frame (mirror postlike).
        val like = BooleanArray(w * h)
        for (y in 0 until h) {
            val rowBase = y.toLong() * stride
            for (x in 0 until w) {
                val idx = (rowBase + x.toLong() * pixelStride).toInt()
                if (idx < 0 || idx + 2 >= b.limit()) continue
                like[y * w + x] = isPostPixel(b, idx)
            }
        }

        // 2. Bars + two-pass measurement (mirror find_bars / analyse_bar).
        val measured = mutableListOf<MeasuredBar>()
        for (raw in findBars(like, w, h)) {
            val m = analyseBar(like, w, h, raw) ?: continue
            measured.add(m)
        }

        // 3. STRONG pool first, then FAR pool; score (both_tall, span,
        // height_sum) — mirror analyse().
        for ((pool, conf) in listOf(
            measured.filter { isStrong(it) } to 0.9f,
            measured.filter { isFar(it) } to 0.65f
        )) {
            var best: PostPair? = null
            var bestTall = -1
            var bestSpan = -1f
            var bestHeight = -1
            for (i in pool.indices) {
                for (j in i + 1 until pool.size) {
                    val l = pool[i].bar
                    val r = pool[j].bar
                    val span = r.cxBot - l.cxBot
                    if (span < MIN_SPAN || span > MAX_SPAN) continue
                    val anchor = (l.bottom >= h - ANCHOR_BOTTOM_MARGIN &&
                        r.bottom >= h - ANCHOR_BOTTOM_MARGIN) ||
                        goalLineOk(like, w, h, l, r)
                    if (!anchor) continue
                    val bothTall = if (l.height >= H_BOTH_TALL && r.height >= H_BOTH_TALL) 1 else 0
                    val heightSum = l.height + r.height
                    val better = bothTall > bestTall ||
                        (bothTall == bestTall && span > bestSpan) ||
                        (bothTall == bestTall && span == bestSpan && heightSum > bestHeight)
                    if (better) {
                        bestTall = bothTall
                        bestSpan = span
                        bestHeight = heightSum
                        best = PostPair(l, r, conf)
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }
}
