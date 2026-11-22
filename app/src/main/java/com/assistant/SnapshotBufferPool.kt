package com.assistant

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * P1 — Snapshot Buffer Reuse (SnapshotBufferPool).
 *
 * Engine-ideas §3 P1. The OCR path allocated one full ARGB_8888 bitmap
 * per 1.5 s tick (`reusableBitmap.copy(...)` in
 * OverlayService.processBitmapForOCR, recycled in the ML Kit completion
 * listener) — ~737 KB of allocation churn per tick at 288x640, under
 * memory pressure. This pool hands out a reused bitmap instead; the
 * caller fills it with an exact byte copy of the source (no rendering
 * pipeline involved) and returns it to the pool when the recognizer is
 * done with it.
 *
 * Performance-rule proof: the pooled bitmap holds byte-identical pixels
 * to the fresh copy it replaces — [fill] is a raw
 * copyPixelsToBuffer/copyPixelsFromBuffer round-trip between two bitmaps
 * of identical size and config, and the recognizer receives the same
 * pixels it received before. Only allocation churn changes: no vision
 * input, no decision input, and no timing threshold is touched. The
 * vision ByteBuffer path (`reusableVisionBuffer`) is already reuse-by-
 * design and is NOT pooled here: its fresh allocation on a generation
 * change is a correctness guard (an abandoned coroutine may still be
 * reading the old buffer), and pooling it would reintroduce that race.
 *
 * Threading: every method is @Synchronized. The pool is used from the
 * OCR HandlerThread (acquire/fill) and the ML Kit completion callback
 * (release), which may run on another thread.
 */
object SnapshotBufferPool {

    /** At most two snapshots in flight (one tick + one slow recognizer). */
    const val MAX_POOL_SIZE = 2

    private val pool = ArrayDeque<Bitmap>()
    private var scratch: ByteBuffer? = null

    // S-4 (Phase S): PoolStats.created/reused/evictedBytesTotal deleted —
    // stats() is read only for .pooled (NonEssentialCacheEvictor:79);
    // the three counters had no reader anywhere (6f verdicts recorded
    // them dead). PoolStats is now pooled-only; the counters and their
    // increments are gone with the fields.
    data class PoolStats(
        val pooled: Int
    )

    fun stats(): PoolStats = synchronized(this) {
        PoolStats(pool.size)
    }

    /** Test hook: recycles every pooled bitmap and clears the counters. No production caller uses it. */
    fun reset() = synchronized(this) {
        for (b in pool) {
            try { b.recycle() } catch (_: Throwable) {}
        }
        pool.clear()
        scratch = null
    }

    /**
     * Returns a bitmap of the requested size/config: a pooled one when
     * available, else a fresh [Bitmap.createBitmap]. The caller must fill
     * it with [fill] before handing it to the recognizer, and must return
     * it with [release] when the recognizer is done.
     */
    fun acquire(width: Int, height: Int, config: Bitmap.Config): Bitmap = synchronized(this) {
        val it = pool.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (!b.isRecycled && b.width == width && b.height == height && b.config == config) {
                it.remove()
                return b
            }
        }
        Bitmap.createBitmap(width, height, config)
    }

    /**
     * Copies [source]'s pixels into [snapshot] exactly. Both bitmaps must
     * have identical size and ARGB_8888 config. Returns false (and leaves
     * [snapshot] untouched) when the copy cannot be done exactly — the
     * caller must then fall back to `source.copy(...)`.
     */
    fun fill(snapshot: Bitmap, source: Bitmap): Boolean = synchronized(this) {
        try {
            if (snapshot.isRecycled || source.isRecycled) return false
            if (snapshot.width != source.width || snapshot.height != source.height) return false
            if (snapshot.config != Bitmap.Config.ARGB_8888 || source.config != Bitmap.Config.ARGB_8888) return false
            val bytes = source.byteCount
            val existing = scratch
            val buf: ByteBuffer = if (existing != null && existing.capacity() >= bytes) existing
            else ByteBuffer.allocateDirect(if (bytes > 0) bytes else 1).also { scratch = it }
            buf.clear()
            source.copyPixelsToBuffer(buf)
            buf.flip()
            snapshot.copyPixelsFromBuffer(buf)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns [bitmap] to the pool when there is room; recycles it when
     * the pool is full. A recycled bitmap is never pooled.
     */
    fun release(bitmap: Bitmap) = synchronized(this) {
        try {
            if (bitmap.isRecycled) return
            if (pool.size >= MAX_POOL_SIZE) {
                bitmap.recycle()
            } else {
                pool.addLast(bitmap)
            }
        } catch (_: Throwable) {}
    }

    /**
     * P4 support: frees every pooled (idle) bitmap plus the scratch
     * buffer, and returns the bytes freed. A checked-out snapshot is
     * never touched — the recognizer still owns it — and the next acquire
     * simply allocates fresh. Called only by NonEssentialCacheEvictor
     * under CRITICAL memory.
     */
    fun evict(): Long = synchronized(this) {
        var freed = 0L
        for (b in pool) {
            try {
                if (!b.isRecycled) freed += b.byteCount.toLong()
                b.recycle()
            } catch (_: Throwable) {}
        }
        pool.clear()
        scratch = null
        freed
    }
}
