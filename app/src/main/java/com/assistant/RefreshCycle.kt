package com.assistant

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P8 — Refresh Fan-Out Dedup (RefreshCycle).
 *
 * Engine-ideas §3 P8. One RuntimePerformanceCoordinator.refresh() used
 * to re-run RuntimeDiagnosticsRegistry.refresh() 4-5x per cycle: once
 * directly, once via RuntimeVisualizationRegistry.refresh(), once via
 * FPSMonitor.refresh(), and once via ConfidenceHeatmap.refresh() — each
 * of those re-running RuntimeOverlayHub / VisionOverlayRegistry /
 * VisionDebugOverlay refreshes and a RuntimeTuningPanel reload. This
 * object gives one coordinator refresh a shared cycle id: the first
 * refresh of each participant in a cycle runs, later claims in the
 * same cycle are skipped.
 *
 * Semantics preserved: outside a cycle, [tryClaim] always returns true
 * — a standalone refresh() always runs fully, exactly as before. Inside
 * a cycle, the first claim runs fully and the later claims are
 * redundant by construction: the coordinator's calls are sequential
 * on one thread, so no writer on that thread can change the engine
 * state between the first and the later claim. (A concurrent writer on
 * another thread could change a value mid-cycle; the pre-existing code
 * had the same race in reverse. The skipped work rebuilds diagnostics
 * / overlay display state only — never a decision input.)
 *
 * [begin]/[end] are reentrant on one thread (nested coordinator calls
 * share the outer cycle id). A refresh() on another thread while a
 * cycle is active on this thread is unaffected (the cycle id is
 * thread-local) and runs fully — safe, never stale by more than the
 * cycle itself.
 *
 * Pure Kotlin (no Android calls). Test hook [resetForTest] clears the
 * claim map; no production caller uses it.
 */
object RefreshCycle {

    private val activeCycle = ThreadLocal<Long>()
    private val activeDepth = ThreadLocal.withInitial { 0 }
    private val claimed = ConcurrentHashMap<String, Long>()
    private val nextId = AtomicLong(1)

    /**
     * Starts (or joins, when nested) a refresh cycle on this thread.
     * Returns the cycle id the caller must pass to [end].
     */
    fun begin(): Long {
        val depth = activeDepth.get() ?: 0
        if (depth > 0) {
            activeDepth.set(depth + 1)
            return activeCycle.get()!!
        }
        val id = nextId.getAndIncrement()
        activeCycle.set(id)
        activeDepth.set(1)
        return id
    }

    /** Ends one [begin] level; the cycle clears when the outermost level ends. */
    fun end(id: Long) {
        if (activeCycle.get() != id) return
        val depth = (activeDepth.get() ?: 1) - 1
        if (depth <= 0) {
            activeCycle.remove()
            activeDepth.remove()
        } else {
            activeDepth.set(depth)
        }
    }

    /**
     * True when [key] should run its refresh now: always true outside a
     * cycle; inside a cycle, only the first claim per key.
     */
    fun tryClaim(key: String): Boolean {
        val cycle = activeCycle.get() ?: return true
        return claimed.put(key, cycle) != cycle
    }

    /** Test hook: clears the claim map. No production caller uses it. */
    fun resetForTest() {
        claimed.clear()
    }
}
