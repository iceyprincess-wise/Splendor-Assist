package com.assistant.overlay.interceptor

import com.assistant.storage.SplendorStorageRoot
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AdaptiveGaugeClassifier — Phase 2 detection stack, engine #2 (kills G9).
 *
 * Replaces the hardcoded RGB-ratio thresholds in
 * ThreatClassifierEngine.classify() with an HSV model that is seeded from
 * those same ratios, then online-learns the gauge colour from confirmed
 * shots and persists the learned model. Pure addition: this file is NEW,
 * no existing file is edited here. The scan wiring that CALLS this
 * classifier instead of ThreatClassifierEngine.classify is the later
 * scan-wiring W piece — it is deliberately NOT done here.
 *
 * ANCHORS (read at HEAD 4bdea3161c230272c3f0e479a4574c598087c52b):
 * - ThreatClassifierEngine.kt — the hardcoded ratios this replaces:
 *     WHITE  r > 190 && g > 190 && b > 190
 *     PURPLE r > 120 && b > 120 && g < 100
 *     GREEN  gRatio > 42 && rRatio < 38
 *     RED    rRatio > 50 && gRatio < 30 && bRatio < 30
 *     ORANGE rRatio > 45 && gRatio > 30 && bRatio < 25
 *     YELLOW rRatio > 40 && gRatio > 40 && bRatio < 25
 *   (ratios are round-half-up shares of r+g+b; ThreatClassifierEngine.kt.)
 * - OmnipotentGoalkeeperEngine.kt:269-270 — scan targets the bottom 25%
 *   (`val startY = (height * 0.75).toInt()`); :284 calls
 *   ThreatClassifierEngine.classify(r, g, b); :304/:307 stride 8 on both
 *   axes; the scan breaks on the first threat. This classifier's scan()
 *   keeps the same first-hit-wins behaviour and the same RGBA layout
 *   (index = y * rowStride + x * 4, channels r/g/b in order).
 * - RuntimeFrame.kt:117-118 — captureWidth / captureHeight (Float, 0f
 *   default); the scan works in capture pixels, the same units the scan's
 *   `width` / `height` parameters use.
 * - GaugeRegionTracker (Phase 2 engine #9, NOT yet on main — staged at
 *   ~/workspace/splendor-assist-gaugeregion/): this classifier takes its
 *   ROI from GaugeRegionTracker.currentRoi(width, height) and NEVER
 *   hardcodes its own scan region. Scan stride is its SCAN_STRIDE_PX (8).
 *   This piece MUST be pushed AFTER the GaugeRegionTracker push.
 * - EngineCreditStore.kt (app, com.assistant) — persistence pattern
 *   followed here: in-memory model is always live; storage is best-effort
 *   and every storage failure fails open to the seeded model. One
 *   correction to the brief: SplendorStorageRoot is NOT SharedPreferences
 *   — it is a FILE root (/sdcard/Splendor-Assist, SplendorStorageRoot.kt
 *   in :core). So the learned model is persisted as a small text file via
 *   SplendorStorageRoot.file(MODEL_FILE), not as prefs; the fail-open
 *   discipline is EngineCreditStore's, the store is the one the spec
 *   named. No new store is invented.
 *
 * HSV SEED TRANSLATION (ratio rule -> HSV centre, computed from a
 * representative RGB pixel that satisfies the rule exactly):
 * - GREEN  rule g 58% / r 25% (rep 60,140,40):  H 108, S 0.71, V 0.55
 * - RED    rule r 64% / g 20% (rep 160,50,40):  H 5,   S 0.75, V 0.63
 * - ORANGE rule r 54% / g 36% (rep 150,100,30): H 35,  S 0.80, V 0.59
 * - YELLOW rule r 45% / g 45% (rep 130,130,30): H 60,  S 0.77, V 0.51
 * - PURPLE rule r 150 / g 70 / b 160:            H 293, S 0.56, V 0.63
 * - WHITE  rule r/g/b > 190: low saturation, high value (S <= 0.15,
 *   V >= 0.72); hue is meaningless for near-white, so it is ignored.
 * Tolerances are deliberately WIDE (hue +/-20-25 deg, S/V +/-0.25) so
 * brightness shifts and anti-aliased gauge edges still match — the
 * hardcoded ratios failed exactly on those. Check order is the original
 * one (WHITE, PURPLE, GREEN, RED, ORANGE, YELLOW) so a pixel matching
 * two bands resolves exactly as ThreatClassifierEngine resolved it.
 *
 * LEARNING BOUND (one bad hit cannot drag the model away):
 * - learn() only accepts a pixel that already scores within 1.5x the
 *   tolerance of its nearest class centre (outlier rejection); anything
 *   further out is ignored, not learned from.
 * - Accepted hits move the centre by EMA alpha 0.05: a single hit moves
 *   hue by at most 0.05 * 180 = 9 deg and S/V by at most 0.05.
 * - Cumulative drift is clamped to +/-15 deg hue and +/-0.15 S/V from
 *   the seed centre. The learned model can therefore never leave the
 *   neighbourhood of the proven ratios, whatever it is fed.
 * - learn() is called ONLY with a confirmed gauge pixel (a shot the
 *   fusion/wiring layer confirmed as a real gauge hit), never with a
 *   mere candidate. The caller owns that confirmation; this file only
 *   bounds what a confirmed pixel may do.
 *
 * CONFIDENCE: 0.5 on the seed (unproven) rising linearly to 1.0 after
 * CONFIDENCE_SAMPLES (32) confirmed learned hits. It measures how much
 * evidence backs the model, not per-pixel match strength; per-pixel
 * strength is GaugeHit.score.
 */
object AdaptiveGaugeClassifier {

    /** One scan result. score = per-pixel match strength (0..1); confidence = model evidence (0..1). */
    data class GaugeHit(
        val found: Boolean,
        val x: Int,
        val y: Int,
        val score: Float,
        val confidence: Float,
    )

    /** Learned-model file, directly under the SplendorStorageRoot root. */
    const val MODEL_FILE: String = "adaptive_gauge_model.txt"

    /** Confirmed hits after which confidence reaches 1.0. */
    const val CONFIDENCE_SAMPLES: Int = 32

    /** Per-hit learning rate (EMA alpha). */
    private const val LEARN_ALPHA: Float = 0.05f

    /** Outlier gate: a learn pixel must be within this multiple of tolerance. */
    private const val LEARN_GATE: Float = 1.5f

    /** Cumulative drift caps, measured from the seed centre. */
    private const val MAX_HUE_DRIFT_DEG: Float = 15f
    private const val MAX_SV_DRIFT: Float = 0.15f

    private const val HUE_TOL_NARROW: Float = 20f
    private const val HUE_TOL_WIDE: Float = 25f
    private const val SV_TOL: Float = 0.25f

    /** One colour class: seed centre, current (learned) centre, tolerances. */
    private data class GaugeClass(
        val type: ThreatType,
        val seedH: Float,
        val seedS: Float,
        val seedV: Float,
        val hueTol: Float,
        val hueAgnostic: Boolean = false,
    ) {
        var h: Float = seedH
        var s: Float = seedS
        var v: Float = seedV
    }

    // Seed centres: the ratio -> HSV translation documented in the header.
    private fun seedModel(): Array<GaugeClass> = arrayOf(
        GaugeClass(ThreatType.WHITE, 0f, 0.00f, 0.85f, HUE_TOL_WIDE, hueAgnostic = true),
        GaugeClass(ThreatType.PURPLE, 293f, 0.56f, 0.63f, HUE_TOL_WIDE),
        GaugeClass(ThreatType.GREEN, 108f, 0.71f, 0.55f, HUE_TOL_WIDE),
        GaugeClass(ThreatType.RED, 5f, 0.75f, 0.63f, HUE_TOL_NARROW),
        GaugeClass(ThreatType.ORANGE, 35f, 0.80f, 0.59f, HUE_TOL_NARROW),
        GaugeClass(ThreatType.YELLOW, 60f, 0.77f, 0.51f, HUE_TOL_NARROW),
    )

    @Volatile private var model: Array<GaugeClass> = seedModel()
    @Volatile private var loaded: Boolean = false
    @Volatile private var learnedSamples: Int = 0

    /** Model evidence: 0.5 on the seed, 1.0 after CONFIDENCE_SAMPLES hits. */
    fun confidence(): Float =
        0.5f + 0.5f * (learnedSamples.toFloat() / CONFIDENCE_SAMPLES).coerceIn(0f, 1f)

    /** Number of confirmed hits learned from (0 = pure seed). */
    fun learnedSamples(): Int = learnedSamples

    /**
     * Drop-in replacement for ThreatClassifierEngine.classify(r, g, b):
     * same signature, same ThreatType return, same check order. HSV match
     * instead of hardcoded ratios. Never throws; any failure returns NONE.
     */
    fun classify(r: Int, g: Int, b: Int): ThreatType {
        return try {
            ensureLoaded()
            match(r, g, b).first
        } catch (_: Throwable) {
            ThreatType.NONE
        }
    }

    /**
     * Scan one frame for a gauge pixel, inside the ROI owned by
     * GaugeRegionTracker — this classifier never hardcodes a scan region.
     * Mirrors the keeper scan: RGBA layout, stride SCAN_STRIDE_PX (8),
     * first match wins. On a hit the tracker's ROI narrows toward it
     * (confirmHit); on a miss it widens back (noteMiss). Never throws;
     * any failure returns found = false.
     */
    fun scan(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int): GaugeHit {
        val noHit = GaugeHit(found = false, x = 0, y = 0, score = 0f, confidence = confidence())
        if (width <= 0 || height <= 0 || rowStride <= 0) return noHit
        return try {
            ensureLoaded()
            val roi = GaugeRegionTracker.currentRoi(width, height)
            val limit = buffer.capacity()
            val stride = GaugeRegionTracker.SCAN_STRIDE_PX
            var y = roi.startY
            while (y < roi.endY) {
                var x = roi.x
                while (x < roi.x + roi.width) {
                    val index = (y * rowStride) + (x * 4)
                    if (index + 2 < limit) {
                        val r = buffer.get(index).toInt() and 0xFF
                        val g = buffer.get(index + 1).toInt() and 0xFF
                        val b = buffer.get(index + 2).toInt() and 0xFF
                        val (type, score) = match(r, g, b)
                        if (type != ThreatType.NONE) {
                            GaugeRegionTracker.confirmHit(x, y)
                            return GaugeHit(
                                found = true,
                                x = x,
                                y = y,
                                score = score,
                                confidence = confidence(),
                            )
                        }
                    }
                    x += stride
                }
                y += stride
            }
            GaugeRegionTracker.noteMiss()
            noHit
        } catch (_: Throwable) {
            noHit
        }
    }

    /**
     * Learn from ONE confirmed gauge pixel (r, g, b of a shot the caller
     * confirmed as a real gauge hit — never a mere candidate). Outlier
     * rejection (LEARN_GATE), EMA alpha 0.05, and the cumulative drift
     * caps in the header bound what one bad hit — or any number of them —
     * can do to the model. Persists best-effort via SplendorStorageRoot;
     * a storage failure changes nothing in memory. Never throws.
     */
    @Synchronized
    fun learn(r: Int, g: Int, b: Int) {
        try {
            ensureLoaded()
            val (h, s, v) = rgbToHsv(r, g, b)
            // Nearest class by normalised distance; reject if outside the gate.
            var best: GaugeClass? = null
            var bestDist = Float.MAX_VALUE
            for (cls in model) {
                val d = normalisedDistance(cls, h, s, v)
                if (d < bestDist) {
                    bestDist = d
                    best = cls
                }
            }
            val target = best ?: return
            if (bestDist > LEARN_GATE) return
            // EMA step toward the confirmed pixel, clamped to the drift caps.
            target.h = clampHueDrift(target, moveHue(target.h, h, LEARN_ALPHA))
            target.s = clampSvDrift(target.seedS, target.s + (s - target.s) * LEARN_ALPHA)
            target.v = clampSvDrift(target.seedV, target.v + (v - target.v) * LEARN_ALPHA)
            learnedSamples++
            saveModel()
        } catch (_: Throwable) {
            // Fail open: the in-memory model is unchanged-or-bounded either way.
        }
    }

    /** Restore the seeded model and clear learned evidence (tests / reset). */
    @Synchronized
    fun reset() {
        model = seedModel()
        learnedSamples = 0
        loaded = true
        try {
            SplendorStorageRoot.file(MODEL_FILE).delete()
        } catch (_: Throwable) {
        }
    }

    /** Centres for diagnostics: "GREEN h=108.0 s=0.71 v=0.55", one per class. */
    @Synchronized
    fun diagnostics(): List<String> {
        ensureLoaded()
        return model.map { cls ->
            "${cls.type} h=${cls.h} s=${cls.s} v=${cls.v}"
        }
    }

    /**
     * Per-pixel match strength for one RGB pixel: the score match() would
     * give (1.0 dead-centre, 0.5 at the tolerance edge), or 0f when no
     * class matches. The native scan path uses this to re-verify its
     * single hit pixel with the Kotlin rule as authority — the C rule
     * scores are all 1.0f by design (see exportRgbRules), so the reported
     * per-pixel score always comes from here, never from C.
     */
    fun matchScore(r: Int, g: Int, b: Int): Float {
        return try {
            ensureLoaded()
            match(r, g, b).second
        } catch (_: Throwable) {
            0f
        }
    }

    /**
     * W-Native (Phase 2): export the CURRENT (learned) model as the flat
     * RGB rule array native_shot_scan expects — 7 floats per class, in
     * the classifier's check order (WHITE, PURPLE, GREEN, RED, ORANGE,
     * YELLOW): [rLo, rHi, gLo, gHi, bLo, bHi, score]. Six rules, 42
     * floats, always <= SHOT_SCAN_MAX_RULES (16). The export reads the
     * live learned centres, never the seeds, so a model that has learned
     * from confirmed hits is what the native scan runs.
     *
     * HSV -> RGB TRANSLATION. Each class's Kotlin rule is a rectangular
     * box in HSV: H in [h - hueTol, h + hueTol] (circular), S and V in
     * [centre - 0.25, centre + 0.25] clamped to [0, 1] — exactly the
     * normalisedDistance <= 1 test. The C rule needs the axis-aligned
     * RGB bounding box of that HSV box's image. That box is computed
     * EXACTLY (not by corner sampling alone): within one 60-degree hue
     * sector, each RGB channel is multilinear in (H, S, V), so a channel
     * attains its extremes at a vertex of the box-sector polytope, and
     * those vertices are either box corners or sector-boundary crossings
     * (H = 60k) with S and V at their extremes. rgbBoundsFor samples
     * exactly that set: the hue interval's endpoints plus every 60-degree
     * boundary inside it, each at the four (S, V) corners.
     *
     * CONSERVATIVENESS: the RGB bounding box is a SUPERSET of the HSV
     * box's image — it contains RGB points whose HSV lies outside the
     * box (the box corners are not themselves in the image). So the C
     * rule can match a pixel the Kotlin rule rejects (false positive),
     * but NEVER rejects a pixel the Kotlin rule accepts (no false
     * negative). That is the safe direction, and it is why the OGE
     * native path re-verifies a native hit with matchScore() and falls
     * back to the full Kotlin scan on a rejected hit — while a native
     * MISS is a true miss (no Kotlin pixel could match) and skips the
     * Kotlin scan.
     *
     * SCORES: every rule's score is 1.0f. C picks the highest-score
     * matching rule, ties to the lowest index — with all scores equal
     * that reproduces the classifier's check order exactly (first
     * matching class in model order wins, as in match()). A per-rule
     * constant would be arbitrary (every class centre scores 1.0 by
     * construction); the real per-pixel score comes from matchScore().
     *
     * WHITE: the WHITE rule is not an HSV box — it is s <= 0.15 and
     * v >= 0.72, hue ignored. Its RGB image is bounded below by
     * min >= 0.85 * 0.72 * 255 = 156.06 on every channel (s <= 0.15
     * forces min >= 0.85 * max, v >= 0.72 forces max >= 183.6), so the
     * WHITE rule is [156, 255] on all three channels. This is the
     * loosest box of the six — it also contains light pastels such as
     * (255, 191, 191), which David's verification showed the HSV rule
     * rejects — so WHITE native hits are the most likely to fail the
     * matchScore() re-verification and fall back to the Kotlin scan.
     * That fallback is the correctness guarantee; the cost is one
     * wasted C scan on such frames, never a wrong hit.
     */
    @Synchronized
    fun exportRgbRules(): FloatArray {
        ensureLoaded()
        val out = FloatArray(model.size * 7)
        for ((i, cls) in model.withIndex()) {
            val bounds = rgbBoundsFor(cls)
            val base = i * 7
            out[base + 0] = bounds[0]
            out[base + 1] = bounds[1]
            out[base + 2] = bounds[2]
            out[base + 3] = bounds[3]
            out[base + 4] = bounds[4]
            out[base + 5] = bounds[5]
            out[base + 6] = 1.0f
        }
        return out
    }

    /**
     * Axis-aligned RGB bounding box of one class's HSV tolerance box:
     * [rLo, rHi, gLo, gHi, bLo, bHi] in 0..255. Exact by the vertex
     * argument in exportRgbRules(). WHITE is the special case (see
     * there): [156, 255] on every channel.
     */
    private fun rgbBoundsFor(cls: GaugeClass): FloatArray {
        if (cls.type == ThreatType.WHITE) {
            // s <= 0.15, v >= 0.72 -> every channel >= 156.06; floor to 156.
            return floatArrayOf(156f, 255f, 156f, 255f, 156f, 255f)
        }
        val sLo = (cls.s - SV_TOL).coerceIn(0f, 1f)
        val sHi = (cls.s + SV_TOL).coerceIn(0f, 1f)
        val vLo = (cls.v - SV_TOL).coerceIn(0f, 1f)
        val vHi = (cls.v + SV_TOL).coerceIn(0f, 1f)
        val hLo = cls.h - cls.hueTol
        val hHi = cls.h + cls.hueTol
        // Hue samples: the interval endpoints plus every 60-degree
        // sector boundary inside the interval (raw hue, may wrap).
        val hues = ArrayList<Float>()
        hues.add(hLo)
        hues.add(hHi)
        var boundary = kotlin.math.ceil(hLo / 60f).toInt() * 60f
        while (boundary < hHi) {
            hues.add(boundary)
            boundary += 60f
        }
        var rLo = 255f
        var rHi = 0f
        var gLo = 255f
        var gHi = 0f
        var bLo = 255f
        var bHi = 0f
        for (hRaw in hues) {
            val h = ((hRaw % 360f) + 360f) % 360f
            for (s in floatArrayOf(sLo, sHi)) {
                for (v in floatArrayOf(vLo, vHi)) {
                    val (r, g, b) = hsvToRgb(h, s, v)
                    if (r < rLo) rLo = r.toFloat()
                    if (r > rHi) rHi = r.toFloat()
                    if (g < gLo) gLo = g.toFloat()
                    if (g > gHi) gHi = g.toFloat()
                    if (b < bLo) bLo = b.toFloat()
                    if (b > bHi) bHi = b.toFloat()
                }
            }
        }
        return floatArrayOf(rLo, rHi, gLo, gHi, bLo, bHi)
    }

    /** HSV (H degrees 0..360, S/V 0..1) to integer RGB 0..255. */
    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(
            ((rf + m) * 255f).toInt().coerceIn(0, 255),
            ((gf + m) * 255f).toInt().coerceIn(0, 255),
            ((bf + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    // ---- matching (allocation-free, same check order as ThreatClassifierEngine) ----

    /** Returns (matched type, match score 0.5..1.0) or (NONE, 0f). */
    private fun match(r: Int, g: Int, b: Int): Pair<ThreatType, Float> {
        val (h, s, v) = rgbToHsv(r, g, b)
        for (cls in model) {
            val d = normalisedDistance(cls, h, s, v)
            if (d <= 1f) {
                // d = 0 is a dead-centre match (score 1.0); d = 1 is the
                // tolerance edge (score 0.5). Anything weaker is no match.
                return cls.type to (1f - 0.5f * d)
            }
        }
        return ThreatType.NONE to 0f
    }

    /**
     * Normalised distance to a class centre: the worst of the three
     * per-channel distances, each divided by its tolerance. <= 1 means
     * "inside tolerance". Hue distance is circular (359 deg and 1 deg are
     * 2 deg apart, so the RED band wraps); WHITE ignores hue entirely.
     */
    private fun normalisedDistance(cls: GaugeClass, h: Float, s: Float, v: Float): Float {
        if (cls.type == ThreatType.WHITE) {
            // Seed rule r/g/b > 190 is low saturation + high value.
            if (s > 0.15f || v < 0.72f) return Float.MAX_VALUE
            return max(s / 0.15f, (1f - v) / 0.28f)
        }
        val hueDist = if (cls.hueAgnostic) 0f else hueDistance(h, cls.h) / cls.hueTol
        val sDist = abs(s - cls.s) / SV_TOL
        val vDist = abs(v - cls.v) / SV_TOL
        return max(hueDist, max(sDist, vDist))
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    /** Shortest-path hue step toward [target] by [alpha] of the distance. */
    private fun moveHue(from: Float, target: Float, alpha: Float): Float {
        var delta = (target - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        var next = (from + delta * alpha) % 360f
        if (next < 0f) next += 360f
        return next
    }

    /** Clamp a learned hue to within MAX_HUE_DRIFT_DEG of its seed (circular). */
    private fun clampHueDrift(cls: GaugeClass, hue: Float): Float {
        val drift = hueDistance(hue, cls.seedH)
        if (drift <= MAX_HUE_DRIFT_DEG) return hue
        // Push back to the cap edge on the side the drift went.
        var delta = (hue - cls.seedH) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val capped = if (delta > 0f) MAX_HUE_DRIFT_DEG else -MAX_HUE_DRIFT_DEG
        var next = (cls.seedH + capped) % 360f
        if (next < 0f) next += 360f
        return next
    }

    private fun clampSvDrift(seed: Float, value: Float): Float =
        value.coerceIn(seed - MAX_SV_DRIFT, seed + MAX_SV_DRIFT).coerceIn(0f, 1f)

    /** Integer RGB (0..255) to HSV: H in degrees 0..360, S and V in 0..1. */
    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = (r.coerceIn(0, 255)) / 255f
        val gf = (g.coerceIn(0, 255)) / 255f
        val bf = (b.coerceIn(0, 255)) / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC
        val v = maxC
        val s = if (maxC == 0f) 0f else delta / maxC
        val h = when {
            delta == 0f -> 0f
            maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
            maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        val hNorm = if (h < 0f) h + 360f else h
        return Triple(hNorm, s, v)
    }

    // ---- persistence (SplendorStorageRoot file; EngineCreditStore fail-open discipline) ----

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val file = SplendorStorageRoot.file(MODEL_FILE)
            if (!file.isFile) return
            val lines = file.readLines()
            if (lines.isEmpty()) return
            val parsed = seedModel()
            var anyValid = false
            for (line in lines) {
                val parts = line.split(",")
                if (parts.size != 4) continue
                val type = try {
                    ThreatType.valueOf(parts[0].trim())
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val h = parts[1].trim().toFloatOrNull() ?: continue
                val s = parts[2].trim().toFloatOrNull() ?: continue
                val v = parts[3].trim().toFloatOrNull() ?: continue
                if (h !in 0f..360f || s !in 0f..1f || v !in 0f..1f) continue
                val cls = parsed.firstOrNull { it.type == type } ?: continue
                // A stored centre outside the drift caps is corruption, not
                // learning — clamp it back inside the caps, never trust it raw.
                cls.h = clampHueDrift(cls, h)
                cls.s = clampSvDrift(cls.seedS, s)
                cls.v = clampSvDrift(cls.seedV, v)
                anyValid = true
            }
            if (anyValid) model = parsed
        } catch (_: Throwable) {
            // Storage unavailable / corrupt / not ready: keep the seed model.
            model = seedModel()
        }
    }

    @Synchronized
    private fun saveModel() {
        try {
            val file = SplendorStorageRoot.file(MODEL_FILE)
            val text = model.joinToString("\n") { cls ->
                "${cls.type},${cls.h},${cls.s},${cls.v}"
            }
            file.writeText(text)
        } catch (_: Throwable) {
            // Best-effort only: the in-memory model is the live one.
        }
    }
}
