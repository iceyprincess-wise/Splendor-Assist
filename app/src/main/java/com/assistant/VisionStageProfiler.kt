package com.assistant

/**
 * B1 — Vision Stage Profiler (engine-ideas §2, Q-N4-REMAIN / Q-N3-1).
 *
 * The wedged vision coroutine's blocking stage was UNPROVEN because no
 * stage timing existed anywhere on the path: OverlayService already had a
 * per-stage heartbeat (`visionStage` + a `stage()` helper that logs only
 * stages over 100 ms), but no `VISION_STAGE <stage>=<ms>` lines and no
 * stuck-stage alert. This profiler is the timing record behind that
 * heartbeat.
 *
 * TRACE: CALLER OverlayService vision coroutine `stage()` → ENGINE this
 * profiler (record per stage) → CALLEE none (pure record) → DATA FLOW
 * stage durations → DECISION slow (>100 ms, already logged by the
 * caller) / stuck (>1000 ms, logged here) / periodic summary →
 * MUTATION per-stage last/max/count + throttled summary lines →
 * CONSUMER the heal log (the next VISION_STUCK line names the stage)
 * → LIVE EFFECT the next wedged-coroutine log names its blocking
 * stage instead of only counting VISION_BUSY_SKIPs. (Item 13: the
 * old header named a self-heal engine as a consumer — it is not;
 * zero occurrences of this profiler in that engine. The log is the
 * only consumer.)
 *
 * Class: diagnostic producer — it only measures. It never touches
 * visionInFlight, the generation, or any dispatch. Log volume is
 * bounded: one summary line at most every 30 s, one stuck line per
 * stage per second at most; the per-frame cost is a map write.
 */
object VisionStageProfiler {

    data class StageStats(
        val lastMs: Long = 0L,
        val maxMs: Long = 0L,
        val count: Long = 0L
    )

    /** A stage slower than this is a stuck stage, not a slow stage. */
    const val STUCK_STAGE_MS = 1000L

    /** At most one summary line per interval, so 20 fps cannot flood the log. */
    const val SUMMARY_INTERVAL_MS = 30_000L

    private val lock = Any()
    private val stats = LinkedHashMap<String, StageStats>()
    private var lastSummaryMs = 0L
    private var lastStuckLogMsByStage = HashMap<String, Long>()

    /** Test hook: clears all recorded stages. No production caller uses it. */
    fun reset() = synchronized(lock) {
        stats.clear()
        lastSummaryMs = 0L
        lastStuckLogMsByStage.clear()
    }

    /**
     * Records one stage's duration. Pure record + bounded logging; safe to
     * call from the vision coroutine on every frame.
     */
    fun record(stage: String, dtMs: Long) {
        if (stage.isBlank() || dtMs < 0L) return
        val nowMs = System.currentTimeMillis()
        var stuck = false
        var summary: String? = null
        synchronized(lock) {
            val prev = stats[stage] ?: StageStats()
            stats[stage] = StageStats(
                lastMs = dtMs,
                maxMs = maxOf(prev.maxMs, dtMs),
                count = prev.count + 1
            )
            if (dtMs > STUCK_STAGE_MS) {
                val lastLog = lastStuckLogMsByStage[stage] ?: 0L
                if (nowMs - lastLog >= 1000L) {
                    lastStuckLogMsByStage[stage] = nowMs
                    stuck = true
                }
            }
            if (nowMs - lastSummaryMs >= SUMMARY_INTERVAL_MS) {
                lastSummaryMs = nowMs
                // Item 13 (half-effect): snapshotLine() inlined — it was
                // a dead public fn (zero callers) whose only use was this
                // summary. Same line, built inline; lastMs()/snapshot()
                // are deleted (zero callers, no test/diagnostics reader).
                summary = if (stats.isEmpty()) "VISION_STAGE (no stages recorded)"
                else "VISION_STAGE " + stats.entries.joinToString(" ") { (name, s) ->
                    "$name=${s.lastMs}ms(max=${s.maxMs}ms,n=${s.count})"
                }
            }
        }
        if (stuck) {
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "VISION_STAGE_STUCK stage=$stage dtMs=$dtMs (vision coroutine stuck in this stage)",
                    "OVERLAY"
                )
            } catch (_: Throwable) { }
        }
        if (summary != null) {
            try {
                com.assistant.diagnostic.RuntimeLogger.log(summary!!, "OVERLAY")
            } catch (_: Throwable) { }
        }
    }

}
