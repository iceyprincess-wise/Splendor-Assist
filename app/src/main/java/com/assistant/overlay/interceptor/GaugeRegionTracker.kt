package com.assistant.overlay.interceptor

/**
 * GaugeRegionTracker — Phase 2 detection stack, engine #9 (the ROI Resource).
 *
 * The AdaptiveGaugeClassifier needs a region to scan instead of the hardcoded
 * bottom-25% region. This tracker owns that region and nothing else: it does
 * not classify pixels, does not dispatch, and does not touch any existing
 * file. It is a pure addition; the scan and the classifier consume it.
 *
 * ANCHORS (read at HEAD 4bdea3161c230272c3f0e479a4574c598087c52b):
 * - OmnipotentGoalkeeperEngine.kt:269-270 — "Target the bottom 25% of the
 *   frame where eFootball power gauges trigger" /
 *   `val startY = (height * 0.75).toInt()`. The scan then walks
 *   y in [startY, height) and x in [0, width), stride 8 on both axes
 *   (:304 `x += 8`, :307 `y += 8`), calling
 *   ThreatClassifierEngine.classify(r, g, b) at :284.
 * - ThreatClassifierEngine.kt — hardcoded ratios the classifier will
 *   replace (GREEN gRatio > 42 && rRatio < 38; RED rRatio > 50 &&
 *   gRatio < 30 && bRatio < 30; ORANGE rRatio > 45 && gRatio > 30 &&
 *   bRatio < 25; YELLOW rRatio > 40 && gRatio > 40 && bRatio < 25;
 *   PURPLE r > 120 && b > 120 && g < 100; WHITE r/g/b > 190).
 * - RuntimeFrame.kt:117-118 — captureWidth / captureHeight (Float, 0f
 *   default); this tracker works in capture pixels, the same units the
 *   scan's `width` / `height` parameters use.
 * - GaugeRegionTracker did NOT exist anywhere in the repo at the base HEAD
 *   (zero hits) — Adaeze's correction stands, so this is a real 9th engine.
 *
 * CONTRACT:
 * - Initial ROI = the scan's own region: x = 0, y = (height * 0.75).toInt(),
 *   width = frame width, height = frame height - y (the bottom 25%).
 *   No new region is hardcoded; the initial ROI is quoted from the scan.
 * - currentRoi(frameWidth, frameHeight) returns the region in capture
 *   pixels for the given frame size. The AdaptiveGaugeClassifier (and the
 *   scan, when wired) calls it per frame and scans x in [roi.x, roi.x +
 *   roi.width), y in [roi.y, roi.y + roi.height), stride SCAN_STRIDE_PX.
 * - confirmHit(x, y) narrows the ROI toward confirmed gauge hits.
 * - noteMiss() widens the ROI back toward the initial region; a long
 *   hit-free stretch expires it back to the initial region entirely.
 * - reset() restores the initial region.
 * - FAIL OPEN: any invalid state (unknown/zero frame size, an out-of-frame
 *   hit, a corrupted ROI) returns the initial bottom-25% region — never an
 *   empty region, never an out-of-frame region.
 *
 * PERSISTENCE: in-memory only, by design (no EngineCreditStore /
 * SharedPreferences use). The ROI is per-session screen geometry plus
 * per-match gauge position: it re-learns within a few confirmed hits, and
 * a persisted ROI would go stale across capture sizes, orientations and
 * matches. The fail-open initial region makes persistence unnecessary.
 */
object GaugeRegionTracker {

    /** Region of interest in capture pixels. Matches the scan's own units. */
    data class GaugeRoi(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        /** Scan start row, in the scan's own `startY` terms. */
        val startY: Int get() = y
        /** Scan end row (exclusive), in the scan's own `height` terms. */
        val endY: Int get() = y + height
    }

    /** Scan stride, quoted from the scan: x += 8 and y += 8 per step. */
    const val SCAN_STRIDE_PX: Int = 8

    /** Initial region: the bottom 25% of the frame (scan's 0.75 factor). */
    const val INITIAL_START_Y_FRACTION: Float = 0.75f

    /** Per confirmed hit, each ROI dimension shrinks 12.5% toward the hit. */
    private const val NARROW_FRACTION: Float = 0.125f

    /** Per miss, each ROI dimension grows 25% back toward the initial region. */
    private const val WIDEN_FRACTION: Float = 0.25f

    /**
     * Narrowing floor, in capture pixels. At the live 288x640 portrait
     * capture the initial ROI is 288x160, so the floor (96x32) is one third
     * of its width and one fifth of its height — small enough to cut the
     * scan cost, large enough that a gauge (which spans far more than 32 px
     * tall) can never be squeezed out. If a frame is smaller than the floor,
     * the floor is clamped to the initial region itself (fail open).
     */
    const val MIN_ROI_WIDTH_PX: Int = 96
    const val MIN_ROI_HEIGHT_PX: Int = 32

    /**
     * Hit-free stretch after which the ROI expires back to the initial
     * region. Gauges appear and disappear with play; a stale narrowed ROI
     * must not outlive the match situation that taught it.
     */
    const val HIT_EXPIRE_MS: Long = 5_000L

    @Volatile private var frameWidth: Int = 0
    @Volatile private var frameHeight: Int = 0
    @Volatile private var roi: GaugeRoi? = null
    @Volatile private var lastHitMs: Long = 0L

    /**
     * The initial bottom-25% region for a frame of the given size.
     * x = 0, y = (height * 0.75).toInt(), width = frame width,
     * height = frame height - y. Returns null for an invalid frame size.
     */
    fun initialRoi(frameWidth: Int, frameHeight: Int): GaugeRoi? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val startY = (frameHeight * INITIAL_START_Y_FRACTION).toInt()
        val height = frameHeight - startY
        if (height <= 0) return null
        return GaugeRoi(x = 0, y = startY, width = frameWidth, height = height)
    }

    /**
     * Current ROI for this frame size, in capture pixels. Call per frame
     * before scanning; the classifier scans x in [roi.x, roi.x + roi.width)
     * and y in [roi.y, roi.y + roi.height), stride [SCAN_STRIDE_PX].
     * Fails open to the initial bottom-25% region whenever the frame size
     * is invalid, the size changed (new capture geometry re-learns from the
     * initial region), or the stored ROI is invalid.
     */
    @Synchronized
    fun currentRoi(frameWidth: Int, frameHeight: Int): GaugeRoi {
        val initial = initialRoi(frameWidth, frameHeight)
            ?: return GaugeRoi(x = 0, y = 0, width = frameWidth.coerceAtLeast(1), height = frameHeight.coerceAtLeast(1))
        if (frameWidth != this.frameWidth || frameHeight != this.frameHeight) {
            this.frameWidth = frameWidth
            this.frameHeight = frameHeight
            roi = initial
            lastHitMs = 0L
            return initial
        }
        val stored = roi ?: initial
        return clampToInitial(stored, initial)
    }

    /**
     * Record a confirmed gauge hit at capture pixel (x, y) — i.e. a pixel
     * the classifier confirmed as gauge, not merely a candidate.
     *
     * NARROWING RULE: the ROI recentres on the hit, then each dimension
     * shrinks by [NARROW_FRACTION] (12.5%) of its current size toward the
     * hit, so repeated hits on the same gauge converge on it while a hit
     * elsewhere drags the window across. The result is clamped to stay a
     * subset of the initial bottom-25% region and never narrower than
     * [MIN_ROI_WIDTH_PX] x [MIN_ROI_HEIGHT_PX] (clamped to the initial
     * region when the frame is smaller than the floor). An out-of-frame hit
     * is ignored — it changes nothing.
     *
     * @param nowMs elapsed-realtime ms (defaults to the live clock); pass an
     *   explicit value only from tests.
     */
    @Synchronized
    fun confirmHit(x: Int, y: Int, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        val initial = initialRoi(frameWidth, frameHeight) ?: return
        if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return
        val current = clampToInitial(roi ?: initial, initial)
        val newWidth = (current.width * (1f - NARROW_FRACTION)).toInt()
            .coerceIn(MIN_ROI_WIDTH_PX.coerceAtMost(initial.width), initial.width)
        val newHeight = (current.height * (1f - NARROW_FRACTION)).toInt()
            .coerceIn(MIN_ROI_HEIGHT_PX.coerceAtMost(initial.height), initial.height)
        val newX = (x - newWidth / 2).coerceIn(initial.x, initial.x + initial.width - newWidth)
        val newY = (y - newHeight / 2).coerceIn(initial.y, initial.y + initial.height - newHeight)
        roi = GaugeRoi(x = newX, y = newY, width = newWidth, height = newHeight)
        lastHitMs = nowMs
    }

    /**
     * Record a frame (or scan pass) with no confirmed gauge hit.
     *
     * WIDENING RULE: each ROI dimension grows by [WIDEN_FRACTION] (25%) of
     * its current size back toward the initial region, recentred on the
     * initial region's centre, so a few misses re-open the window instead of
     * leaving it stranded on a gauge that moved. If no hit has been seen for
     * [HIT_EXPIRE_MS], the ROI expires back to the initial region entirely.
     *
     * @param nowMs elapsed-realtime ms (defaults to the live clock); pass an
     *   explicit value only from tests.
     */
    @Synchronized
    fun noteMiss(nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        val initial = initialRoi(frameWidth, frameHeight) ?: return
        if (lastHitMs != 0L && nowMs - lastHitMs >= HIT_EXPIRE_MS) {
            roi = initial
            lastHitMs = 0L
            return
        }
        val current = clampToInitial(roi ?: initial, initial)
        if (current == initial) return
        val newWidth = (current.width * (1f + WIDEN_FRACTION)).toInt()
            .coerceAtMost(initial.width)
        val newHeight = (current.height * (1f + WIDEN_FRACTION)).toInt()
            .coerceAtMost(initial.height)
        val newX = (initial.x + (initial.width - newWidth) / 2)
            .coerceIn(initial.x, initial.x + initial.width - newWidth)
        val newY = (initial.y + (initial.height - newHeight) / 2)
            .coerceIn(initial.y, initial.y + initial.height - newHeight)
        roi = GaugeRoi(x = newX, y = newY, width = newWidth, height = newHeight)
    }

    /** Restore the initial bottom-25% region (and clear the hit clock). */
    @Synchronized
    fun reset() {
        roi = initialRoi(frameWidth, frameHeight)
        lastHitMs = 0L
    }

    /**
     * Clamp a stored ROI to a valid subset of the initial region: inside the
     * frame, inside the initial region, non-empty, and at least the
     * narrowing floor (or the initial region itself when the frame is
     * smaller than the floor). Any violation falls back to the initial
     * region — this is the fail-open path.
     */
    private fun clampToInitial(candidate: GaugeRoi, initial: GaugeRoi): GaugeRoi {
        if (candidate.width <= 0 || candidate.height <= 0) return initial
        val minWidth = MIN_ROI_WIDTH_PX.coerceAtMost(initial.width)
        val minHeight = MIN_ROI_HEIGHT_PX.coerceAtMost(initial.height)
        val width = candidate.width.coerceIn(minWidth, initial.width)
        val height = candidate.height.coerceIn(minHeight, initial.height)
        val x = candidate.x.coerceIn(initial.x, initial.x + initial.width - width)
        val y = candidate.y.coerceIn(initial.y, initial.y + initial.height - height)
        return GaugeRoi(x = x, y = y, width = width, height = height)
    }
}
