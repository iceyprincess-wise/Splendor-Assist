package com.assistant

/**
 * Queue item 7 (F5) — end-to-end latency measurement (Ed's addition,
 * Sat 2026-11-21 ~00:21 WAT). MEASUREMENT ONLY: nothing here changes
 * any behaviour, any threshold, or the leadMs cap. The 100 vs 200 ms
 * leadMs decision is made on the logs this produces, not guessed.
 *
 * TRACE: CALLER FrameAssembler.assemble (saturation + age) /
 * OverlayService after stage("assemble") (queue + vision legs) /
 * RuntimeDecisionLoop.onFrame accepted branch (end-to-end + decide
 * leg) / ActionOutcomeVerifier.verify on a confirmed effect
 * (dispatch→effect) → ENGINE this monitor → CALLEE none (pure record
 * + one throttled log line) → DATA FLOW per-frame timestamps →
 * DECISION none (it never decides anything) → MUTATION window
 * counters + bounded histogram → CONSUMER the 30 s PIPELINE_LATENCY
 * log line → LIVE EFFECT Ed's next session logs carry the numbers
 * the leadMs 100 vs 200 call needs.
 *
 * What is measured (all `System.nanoTime()`, monotonic):
 *  - age at assemble: assemble time − frame timestamp; saturated ⟺
 *    leadMs >= 100f (the capped value — exact, no derivation).
 *  - queue leg: vision start − capture (ImageReader queue + listener).
 *  - vision leg: assemble-end − vision start.
 *  - end-to-end: dispatch (route() accepted) − capture.
 *  - decide leg: dispatch − assemble-end. The assemble-end timestamp
 *    is stashed volatile by recordVision; single-flight vision (one
 *    coroutine, assemble → onFrame sequential) makes the stash this
 *    frame's value. Guarded: a decide leg outside [0, end-to-end] is
 *    skipped, never recorded.
 *  - effect leg (dispatch→on-screen PROXY, not a screen timestamp):
 *    ms + frames from dispatch to the first frame where verify()
 *    confirms the effect via FrameEffectVerifier. Resolution is one
 *    frame (~50 ms at 20 fps); only confirmed effects sample.
 *
 * Cost + log discipline (same shape as VisionStageProfiler):
 *  - Hot path per frame: a few integer ops + one histogram-bucket
 *    increment + one volatile write. No string building, no
 *    allocation, no I/O on the hot path.
 *  - NO log line per frame. Counters accumulate; ONE summary line at
 *    most every 30 s (same cadence as the COMPLIANCE heartbeat and
 *    VisionStageProfiler.SUMMARY_INTERVAL_MS). Windowed stats — each
 *    line is one self-contained 30 s window, counters reset after
 *    emission.
 *  - Every public record method is try/catch-guarded and never throws:
 *    a measurement bug must never break the pipeline it measures.
 *
 * End-to-end distribution: bounded 11-bucket histogram, edges
 * 10/20/30/40/50/60/80/100/150/250 ms. p50/p95 are bucket-midpoint
 * approximations (±half a bucket width); the open top bucket reports
 * its lower edge. Documented as approximate wherever they are read.
 */
object PipelineLatencyMonitor {

    /** One summary line at most per interval, so 20 fps cannot flood the log. */
    const val SUMMARY_INTERVAL_MS = 30_000L

    /**
     * Mirrors FrameAssembler's `coerceAtMost(100f)` — the saturation
     * test `leadMs >= LEAD_MS_CAP` reads it. MEASUREMENT ONLY: this
     * constant never clamps anything here; the cap itself lives in
     * FrameAssembler and stays at 100 until Ed decides on the logs.
     */
    const val LEAD_MS_CAP = 100f

    /** Upper bound for a sane leg sample; beyond it the frame was wedged, not slow. */
    private const val MAX_LEG_MS = 5_000L
    private const val MAX_E2E_MS = 10_000L

    /** Histogram edges (ms); 10 edges → 11 buckets, the last open-ended. */
    private val BUCKET_EDGES_MS = longArrayOf(10L, 20L, 30L, 40L, 50L, 60L, 80L, 100L, 150L, 250L)

    private val lock = Any()

    // --- window counters (reset after each summary emission) ---
    private var assembled = 0L
    private var saturated = 0L
    private var ageSumMs = 0L
    private var ageCount = 0L
    private var queueSumMs = 0L
    private var queueCount = 0L
    private var visionSumMs = 0L
    private var visionCount = 0L
    private var decideSumMs = 0L
    private var decideCount = 0L
    private val e2eBuckets = LongArray(BUCKET_EDGES_MS.size + 1)
    private var e2eCount = 0L
    private var e2eMinMs = Long.MAX_VALUE
    private var e2eMaxMs = 0L
    private var effectCount = 0L
    private var effectSumMs = 0L
    private var effectSumFrames = 0L

    @Volatile private var lastAssembleEndNanos = 0L
    private var lastSummaryMs = 0L

    /** Test hook: clears all window counters. No production caller uses it. */
    fun reset() = synchronized(lock) {
        assembled = 0L
        saturated = 0L
        ageSumMs = 0L
        ageCount = 0L
        queueSumMs = 0L
        queueCount = 0L
        visionSumMs = 0L
        visionCount = 0L
        decideSumMs = 0L
        decideCount = 0L
        e2eBuckets.fill(0L)
        e2eCount = 0L
        e2eMinMs = Long.MAX_VALUE
        e2eMaxMs = 0L
        effectCount = 0L
        effectSumMs = 0L
        effectSumFrames = 0L
        lastAssembleEndNanos = 0L
        lastSummaryMs = 0L
    }

    /**
     * Called from FrameAssembler.assemble() once per assembled frame,
     * after leadMs is computed. Records the age at assemble time and
     * whether the 100 ms cap bound the prediction lead.
     */
    fun recordAssemble(capturedAtNanos: Long, assembleNanos: Long, leadMs: Float) {
        var line: String? = null
        try {
            synchronized(lock) {
                assembled++
                if (leadMs >= LEAD_MS_CAP) saturated++
                // Guard on the raw nanos delta: ms truncation would turn
                // a sub-millisecond negative delta into a valid-looking
                // 0 ms sample.
                val ageNanos = assembleNanos - capturedAtNanos
                if (ageNanos >= 0L) {
                    val ageMs = ageNanos / 1_000_000L
                    if (ageMs <= MAX_LEG_MS) {
                        ageSumMs += ageMs
                        ageCount++
                    }
                }
                line = maybeEmitLocked()
            }
        } catch (_: Throwable) { }
        if (line != null) emit(line!!)
    }

    /**
     * Called from OverlayService once per assembled frame, right after
     * stage("assemble") — t0 there is the assemble-end timestamp.
     * Records the queue leg (capture → vision start) and the vision leg
     * (vision start → assemble-end), and stashes the assemble-end
     * timestamp for the decide leg in recordDispatch.
     */
    fun recordVision(capturedAtNanos: Long, visionStartedNanos: Long, assembleEndNanos: Long) {
        try {
            synchronized(lock) {
                lastAssembleEndNanos = assembleEndNanos
                // Guards on the raw nanos deltas (see recordAssemble).
                val queueNanos = visionStartedNanos - capturedAtNanos
                if (queueNanos >= 0L) {
                    val queueMs = queueNanos / 1_000_000L
                    if (queueMs <= MAX_LEG_MS) {
                        queueSumMs += queueMs
                        queueCount++
                    }
                }
                val visionNanos = assembleEndNanos - visionStartedNanos
                if (visionNanos >= 0L) {
                    val visionMs = visionNanos / 1_000_000L
                    if (visionMs <= MAX_LEG_MS) {
                        visionSumMs += visionMs
                        visionCount++
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    /**
     * Called from RuntimeDecisionLoop.onFrame for ACCEPTED dispatches
     * only — a rejected route never reaches the screen. Records the
     * capture→dispatch end-to-end latency and the decide leg
     * (assemble-end → dispatch).
     */
    fun recordDispatch(capturedAtNanos: Long, dispatchNanos: Long) {
        var line: String? = null
        try {
            synchronized(lock) {
                // Guard on the raw nanos delta (see recordAssemble).
                val e2eNanos = dispatchNanos - capturedAtNanos
                if (e2eNanos >= 0L) {
                    val e2eMs = e2eNanos / 1_000_000L
                    if (e2eMs <= MAX_E2E_MS) {
                        e2eBuckets[bucketFor(e2eMs)]++
                        e2eCount++
                        if (e2eMs < e2eMinMs) e2eMinMs = e2eMs
                        if (e2eMs > e2eMaxMs) e2eMaxMs = e2eMs
                        val assembleEnd = lastAssembleEndNanos
                        if (assembleEnd != 0L) {
                            val decideNanos = dispatchNanos - assembleEnd
                            if (decideNanos in 0L..e2eNanos) {
                                decideSumMs += decideNanos / 1_000_000L
                                decideCount++
                            }
                        }
                    }
                }
                line = maybeEmitLocked()
            }
        } catch (_: Throwable) { }
        if (line != null) emit(line!!)
    }

    /**
     * Called from ActionOutcomeVerifier.verify() when an effect is
     * CONFIRMED (effectConfirmed != null). dispatchAtNanos is the
     * Pending.at timestamp (recordDispatch time); observedNanos is the
     * confirming frame's verify() time. Only confirmed effects sample —
     * a failed effect contributes no sample (the failure counters
     * already exist in the verifier).
     */
    fun recordEffect(dispatchAtNanos: Long, observedNanos: Long, preFrameId: Long, currentFrameId: Long) {
        try {
            synchronized(lock) {
                // Guard on the raw nanos delta (see recordAssemble).
                val effectNanos = observedNanos - dispatchAtNanos
                val frames = currentFrameId - preFrameId
                if (effectNanos >= 0L && frames in 0L..600L) {
                    val effectMs = effectNanos / 1_000_000L
                    if (effectMs <= MAX_LEG_MS) {
                        effectCount++
                        effectSumMs += effectMs
                        effectSumFrames += frames
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    /** Bucket index for an end-to-end sample (ms). */
    private fun bucketFor(ms: Long): Int {
        var i = 0
        while (i < BUCKET_EDGES_MS.size && ms >= BUCKET_EDGES_MS[i]) i++
        return i
    }

    /**
     * Approximate percentile of the end-to-end distribution: the
     * midpoint of the bucket holding the p-th percentile (±half a
     * bucket width); the open top bucket reports its lower edge.
     * Returns -1 when the window has no dispatch sample. Single
     * implementation: the locked walk below; this is its delegate.
     */
    fun e2ePercentile(p: Double): Long = synchronized(lock) { e2ePercentileLocked(p) }

    /** Full window copy for tests / diagnostics readers. */
    fun snapshot(): Map<String, Long> = synchronized(lock) {
        mapOf(
            "assembled" to assembled,
            "saturated" to saturated,
            "ageMeanMs" to if (ageCount > 0L) ageSumMs / ageCount else -1L,
            "queueMeanMs" to if (queueCount > 0L) queueSumMs / queueCount else -1L,
            "visionMeanMs" to if (visionCount > 0L) visionSumMs / visionCount else -1L,
            "decideMeanMs" to if (decideCount > 0L) decideSumMs / decideCount else -1L,
            "e2eCount" to e2eCount,
            "e2eMinMs" to if (e2eCount > 0L) e2eMinMs else -1L,
            "e2eMaxMs" to if (e2eCount > 0L) e2eMaxMs else -1L,
            "e2eP50Ms" to e2ePercentileLocked(50.0),
            "e2eP95Ms" to e2ePercentileLocked(95.0),
            "effectCount" to effectCount,
            "effectMeanMs" to if (effectCount > 0L) effectSumMs / effectCount else -1L,
            "effectMeanFramesTenths" to if (effectCount > 0L) effectSumFrames * 10L / effectCount else -1L
        )
    }

    /** Same percentile walk as e2ePercentile, for use with the lock already held. */
    private fun e2ePercentileLocked(p: Double): Long {
        if (e2eCount == 0L) return -1L
        val target = kotlin.math.ceil(p / 100.0 * e2eCount.toDouble()).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in e2eBuckets.indices) {
            cumulative += e2eBuckets[i]
            if (cumulative >= target) {
                if (i == e2eBuckets.lastIndex) return BUCKET_EDGES_MS.last()
                val lower = if (i == 0) 0L else BUCKET_EDGES_MS[i - 1]
                return (lower + BUCKET_EDGES_MS[i]) / 2L
            }
        }
        return BUCKET_EDGES_MS.last()
    }

    /**
     * Builds the one summary line for the current window. Does NOT
     * reset and does NOT log — the caller (maybeEmitLocked) owns that.
     * Exact format, pinned in the harness and recorded in TRACE.md §7.4
     * so Ed's logs can be read against it. Single source of the format:
     * the logged line and the tested line are the same function.
     */
    fun summaryLine(): String = synchronized(lock) { summaryLineLocked() }

    /**
     * Called with the lock held by the record methods. Returns the
     * summary line when the interval has elapsed (and resets the
     * window), else null. The log write happens outside the lock.
     * The first record after start/reset ARMS the window instead of
     * emitting — otherwise the first frame's sample would be emitted
     * alone and then reset away.
     */
    private fun maybeEmitLocked(): String? {
        val nowMs = System.currentTimeMillis()
        if (lastSummaryMs == 0L) {
            lastSummaryMs = nowMs
            return null
        }
        if (nowMs - lastSummaryMs < SUMMARY_INTERVAL_MS) return null
        lastSummaryMs = nowMs
        val line = summaryLineLocked()
        resetLocked()
        return line
    }

    /** summaryLine() with the lock already held (same body, no re-lock). */
    private fun summaryLineLocked(): String {
        if (assembled == 0L) return "PIPELINE_LATENCY (no frames)"
        val ageMean = if (ageCount > 0L) "${ageSumMs / ageCount}" else "-"
        val queueMean = if (queueCount > 0L) "${queueSumMs / queueCount}" else "-"
        val visionMean = if (visionCount > 0L) "${visionSumMs / visionCount}" else "-"
        val decideMean = if (decideCount > 0L) "${decideSumMs / decideCount}" else "-"
        val e2ePart = if (e2eCount > 0L) {
            "e2eN=$e2eCount e2eMin=$e2eMinMs e2eP50=${e2ePercentileLocked(50.0)} " +
                "e2eP95=${e2ePercentileLocked(95.0)} e2eMax=$e2eMaxMs"
        } else {
            "e2eN=0 e2eMin=- e2eP50=- e2eP95=- e2eMax=-"
        }
        val satTenths = if (assembled > 0L) saturated * 1000L / assembled else 0L
        val effectPart = if (effectCount > 0L) {
            val framesTenths = effectSumFrames * 10L / effectCount
            "effectN=$effectCount effectMeanMs=${effectSumMs / effectCount} " +
                "effectFrames=${framesTenths / 10}.${framesTenths % 10}"
        } else {
            "effectN=0 effectMeanMs=- effectFrames=-"
        }
        return "PIPELINE_LATENCY asm=$assembled ageMean=$ageMean $e2ePart " +
            "queueMean=$queueMean visionMean=$visionMean decideMean=$decideMean " +
            "sat=$saturated/$assembled ${satTenths / 10}.${satTenths % 10}% $effectPart"
    }

    /** Window reset with the lock already held. */
    private fun resetLocked() {
        assembled = 0L
        saturated = 0L
        ageSumMs = 0L
        ageCount = 0L
        queueSumMs = 0L
        queueCount = 0L
        visionSumMs = 0L
        visionCount = 0L
        decideSumMs = 0L
        decideCount = 0L
        e2eBuckets.fill(0L)
        e2eCount = 0L
        e2eMinMs = Long.MAX_VALUE
        e2eMaxMs = 0L
        effectCount = 0L
        effectSumMs = 0L
        effectSumFrames = 0L
        lastAssembleEndNanos = 0L
    }

    private fun emit(line: String) {
        try {
            com.assistant.diagnostic.RuntimeLogger.log(line, "LATENCY")
        } catch (_: Throwable) { }
    }
}
