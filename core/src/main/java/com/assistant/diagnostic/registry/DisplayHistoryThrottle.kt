package com.assistant.diagnostic.registry

/**
 * P5 — Display Telemetry Throttle (DisplayHistoryThrottle).
 *
 * Engine-ideas §3 P5. PerformanceTelemetryRegistry.publishDisplay is
 * called every 500 ms by LagVerdictEngine, and every call appended a
 * line to perf_display_history.txt (open/append/close, plus a whole-
 * file read+rewrite whenever the history exceeds its cap) although
 * nothing reads that stream for decisions — its only reader is
 * FilterEngine's report, which prints the verdict counts of the last
 * 10 lines. This throttle gates ONLY the history-file append: at most
 * one append per [MIN_INTERVAL_MS], plus an immediate append whenever
 * the verdict changes, so a SMOOTH -> CHOKING transition is never
 * delayed. The single-line current value (perf_display.txt), the
 * in-memory verdict fields, and the cross-process device snapshot
 * (updateDeviceState) are untouched and stay at full rate.
 *
 * Performance-rule proof: the throttled stream is display-only. The
 * heal, the loop, and the filter read different streams (F47FPS /
 * F47MEM / F47NET) or the unthrottled current value — so no decision,
 * heal, or filter input changes. The FilterEngine trend reader still
 * sees the last 10 verdicts, now spanning ~50 s instead of ~5 s.
 *
 * Pure Kotlin (no Android calls). The caller passes wall-clock
 * milliseconds so the decision is testable.
 */
class DisplayHistoryThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5000L
    }

    private var lastAppendMs = 0L
    private var lastVerdict: String? = null

    /** True when this sample should be appended to the history file. */
    @Synchronized
    fun shouldAppend(verdict: String, nowMs: Long): Boolean {
        if (verdict != lastVerdict) {
            lastVerdict = verdict
            lastAppendMs = nowMs
            return true
        }
        if (nowMs - lastAppendMs >= minIntervalMs) {
            lastAppendMs = nowMs
            return true
        }
        return false
    }

    // Item 17 (Letter D, D2-F06): reset() is DELETED — it was a test
    // hook with zero production callers, and tests get a clean state
    // from a fresh DisplayHistoryThrottle() instance (default 5000 ms
    // interval), so the hook buys nothing. shouldAppend is untouched.
    // (No production caller existed to update.)
}
