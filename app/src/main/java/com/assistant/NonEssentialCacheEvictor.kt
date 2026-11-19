package com.assistant

import com.assistant.diagnostic.LogMirror

/**
 * P4 — Non-Essential Cache Evictor (NonEssentialCacheEvictor).
 *
 * Engine-ideas §3 P4, under Ed's D1 decision: CRITICAL memory must NOT
 * slow in-game capture (V10 latency wins), so the brake sheds load
 * another way — by freeing memory held in caches no gameplay engine
 * reads. Called by MemoryMonitorEngine on a CRITICAL tier, at most once
 * per [MIN_INTERVAL_MS].
 *
 * Allow-list (reader-grep verified in the implementing round; every
 * entry below names its readers, and anything with a gameplay reader is
 * excluded by construction):
 *  1. Pooled OCR snapshots (SnapshotBufferPool): idle bitmaps waiting
 *     for the next OCR tick. Readers: only OverlayService
 *     .processBitmapForOCR, via acquire/fill/release — a checked-out
 *     snapshot is never touched, and the next acquire simply allocates
 *     fresh. Freed: up to MAX_POOL_SIZE bitmaps.
 *  2. Stale routine log records queued in LogMirror: records with no
 *     crash waiter (completion == null). Crash-waiter records are
 *     always kept — a crash line is never dropped by this evictor.
 *     Readers: the LogMirror writer thread (file sink only); no
 *     decision reads the queue.
 *
 * Explicitly NOT evicted (negative allow-list — a source pin asserts
 * none of these names appears in this file):
 *  - previousVisionBuffer / reusableVisionBuffer / reusableBitmap
 *    (dedup, optical variance, and OCR all read them live),
 *  - SceneTracker tracked players, TemporalMemoryEngine state,
 *    TelemetryRepository snapshot (gameplay state),
 *  - telemetry history FILES (perf_*_history.txt): truncating them
 *    frees disk, not RAM, so it cannot raise availMem — out of scope
 *    for this evictor, which frees RAM only.
 *
 * Performance-rule proof: the evictor frees bytes from caches no
 * gameplay engine reads and never changes a capture interval, a vision
 * input, or a decision input. D1 holds — capture rate is untouched.
 * The freed total is small by design (the census found no large
 * evictable in-app RAM cache: every retained structure is either a
 * small fixed-size ring or live gameplay state); the evictor frees
 * what honestly exists instead of inventing a cache to evict.
 */
object NonEssentialCacheEvictor {

    /** Minimum time between evictions while CRITICAL persists. */
    const val MIN_INTERVAL_MS = 60_000L

    @Volatile private var lastEvictMs = 0L

    data class EvictionReport(
        val freedBytes: Long,
        val evictedSnapshots: Int,
        val droppedLogLines: Long,
        val droppedLogBytes: Long
    )

    /** Test hook: clears the throttle so the next call runs. No production caller uses it. */
    fun resetThrottle() {
        lastEvictMs = 0L
    }

    /**
     * Evicts when due (at most once per [MIN_INTERVAL_MS]); returns null
     * when throttled. The caller logs the report only when freedBytes > 0.
     */
    fun evictIfDue(nowMs: Long = System.currentTimeMillis()): EvictionReport? {
        if (nowMs - lastEvictMs < MIN_INTERVAL_MS) return null
        lastEvictMs = nowMs
        return evict()
    }

    fun evict(): EvictionReport {
        var freed = 0L
        var snapshots = 0
        try {
            snapshots = SnapshotBufferPool.stats().pooled
            freed += SnapshotBufferPool.evict()
        } catch (_: Throwable) {}
        var droppedLines = 0L
        var droppedBytes = 0L
        try {
            val dropped = LogMirror.dropQueuedRoutine()
            droppedLines = dropped.count
            droppedBytes = dropped.bytes
            freed += droppedBytes
        } catch (_: Throwable) {}
        return EvictionReport(freed, snapshots, droppedLines, droppedBytes)
    }

    fun logLine(report: EvictionReport): String =
        "CACHE_EVICT freedBytes=${report.freedBytes} evictedSnapshots=${report.evictedSnapshots} " +
            "droppedLogLines=${report.droppedLogLines} droppedLogBytes=${report.droppedLogBytes} " +
            "(CRITICAL: pooled OCR snapshots + queued routine log lines only; capture rate untouched)"
}
