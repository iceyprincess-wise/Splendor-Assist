package com.assistant.diagnostic

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * P2 — Log Writer Batching (LogBatcher).
 *
 * Engine-ideas §3 P2. RuntimeLogger.append() used to open a new
 * FileOutputStream, write one line, and close it — one open/write/close
 * syscall triple per log line (~20 lines/s in a session) on the
 * RuntimeLogIO thread. This batcher keeps one buffered stream per file
 * on that thread and flushes on a byte threshold, an age threshold, or
 * immediately for urgent lines — so the file content stays
 * byte-identical while the open/close churn disappears.
 *
 * Flush rules (all evaluated on the single RuntimeLogIO thread):
 *  - urgent line (CRITICAL / FATAL / heal-agent tag — see
 *    RuntimeLogger.log): flush immediately, before returning;
 *  - buffered bytes >= [FLUSH_BYTES]: flush;
 *  - oldest buffered line older than [FLUSH_AGE_MS]: flush on the next
 *    append (bounds any routine line's disk delay to ~1 s).
 *
 * Threading: every method here MUST be called only from the single
 * RuntimeLogIO thread (all callers funnel through RuntimeLogger
 * .append, which runs on that executor). The map is deliberately not
 * synchronized — a second writer thread would break the batching
 * contract. [appendSync] (the execution-chain death-window record) is
 * NOT batched and never calls this object.
 *
 * Rotation: the caller flushes ([flush]) before rotating a file, so no
 * buffered byte can land in the renamed file. If the file vanishes or
 * shrinks under the batcher (retention sweep, external rotation), the
 * stream is closed and reopened on the next append — the check is a
 * cheap stat, not an open.
 *
 * Performance-rule proof: log content is byte-identical (same lines,
 * same order — one thread, one stream per file); only write batching
 * changes. No heal or filter decision reads these files at runtime
 * (the heal consumes in-memory state; TruthLogcatEngine tails the
 * field log for display and matches CRITICAL lines, which flush
 * immediately), so no decision is delayed.
 */
object LogBatcher {

    /** Flush once this many bytes are buffered for one file. */
    const val FLUSH_BYTES = 8192L

    /** Flush when the oldest buffered line is this old (bounds disk delay). */
    const val FLUSH_AGE_MS = 1000L

    private class Sink(val file: File) {
        var out: BufferedOutputStream? = null
        var bufferedBytes = 0L
        var oldestBufferedMs = 0L
        var baseLength = 0L
    }

    // Single-threaded use only (RuntimeLogIO thread) — see the class KDoc.
    private val sinks = HashMap<File, Sink>()

    /**
     * Appends [text] to [file]'s batched stream. [urgent] lines flush
     * immediately; routine lines flush on the byte/age thresholds.
     * Rotation stays the caller's job (flush first, then rotate, then
     * append) — this object never rotates.
     */
    fun append(file: File, text: String, urgent: Boolean) {
        val nowMs = System.currentTimeMillis()
        var sink = sinks[file]
        if (sink == null) {
            sink = Sink(file)
            sinks[file] = sink
        }
        // External rotation/deletion: reopen when the path vanished or
        // shrank below the length the stream was opened at.
        if (sink.out != null && (!file.exists() || file.length() < sink.baseLength)) {
            flushSink(sink)
            closeSink(sink)
        }
        if (sink.out == null) {
            sink.baseLength = if (file.exists()) file.length() else 0L
            sink.out = BufferedOutputStream(FileOutputStream(file, true), 8192)
            sink.bufferedBytes = 0L
            sink.oldestBufferedMs = 0L
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        sink.out!!.write(bytes)
        if (sink.bufferedBytes == 0L) sink.oldestBufferedMs = nowMs
        sink.bufferedBytes += bytes.size
        if (urgent || sink.bufferedBytes >= FLUSH_BYTES || nowMs - sink.oldestBufferedMs >= FLUSH_AGE_MS) {
            flushSink(sink)
        }
    }

    /** Pushes every buffered byte for [file] to disk; keeps the stream open. */
    fun flush(file: File) {
        sinks[file]?.let { flushSink(it) }
    }

    /** Test hook: flushes and closes every stream. No production caller uses it. */
    fun resetForTest() {
        for (sink in sinks.values) {
            try { flushSink(sink) } catch (_: Throwable) {}
            closeSink(sink)
        }
        sinks.clear()
    }

    private fun flushSink(sink: Sink) {
        try {
            sink.out?.flush()
        } catch (_: Throwable) {}
        sink.bufferedBytes = 0L
        sink.oldestBufferedMs = 0L
    }

    private fun closeSink(sink: Sink) {
        try {
            sink.out?.close()
        } catch (_: Throwable) {}
        sink.out = null
        sink.bufferedBytes = 0L
        sink.oldestBufferedMs = 0L
        sink.baseLength = 0L
    }
}
