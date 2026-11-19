package com.assistant.diagnostic

import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Temporary, best-effort app log mirror. Producer calls only enqueue bounded strings.
 *
 * no59 ITEM 3 — ONE PATH: this was the stray writer Ed saw ("Strike.log
 * in /downloads"). There is no file named Strike.log anywhere in the
 * repo (git grep finds nothing); this is the writer he meant — its file
 * is stroke_log.txt, and it wrote to the shared Downloads collection
 * via MediaStore (API 31+) or getExternalFilesDir(DIRECTORY_DOWNLOADS)
 * below it, outside the canonical root every other logger uses. The
 * sink is now a plain file under SplendorStorageRoot, i.e.
 * /sdcard/Splendor-Assist/stroke_log.txt (rotation to
 * stroke_log.old.txt, same 8MB cap). The file NAME is unchanged — only
 * the path moved — so anything that already reads stroke_log.txt keeps
 * working. The old MediaStore entry in Downloads is left alone (the
 * mirror never deletes user-visible files it did not just rotate); the
 * new file is the live one from this round on.
 *
 * The enqueue/mirror API is unchanged (mirror, mirrorCrashAndWait,
 * droppedCount, init); RuntimeLogger.log() still feeds it every entry.
 * The writer thread, the bounded queue, the per-record flush and the
 * rotation rule are unchanged — only the sink moved.
 */
object LogMirror {
    private const val FILE_NAME = "stroke_log.txt"
    private const val OLD_FILE_NAME = "stroke_log.old.txt"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private const val QUEUE_CAPACITY = 4096
    // F42-D7: the old comment claimed these tags were "explicitly
    // discovered in the pinned engine sources". The RuntimeLogger hook
    // captures every tag, not only a registry - there is no pinned tag
    // list, and none is needed. (The oddly indented LogRecord line below
    // it is also fixed.)
    // P2: the record keeps its priority — the writer flushes immediately
    // for ERROR+ (crash lines), and batches routine lines instead of
    // flushing after every record.
    private data class LogRecord(val line: String, val priority: Int = android.util.Log.INFO, val completion: java.util.concurrent.CountDownLatch? = null)
    private val queue = LinkedBlockingQueue<LogRecord>(QUEUE_CAPACITY)
    // F42-D4: enqueue used to drop records silently (full queue, or a call
    // before init) with no count anywhere. Every lost record is counted.
    @Volatile private var droppedRecords = 0L
    fun droppedCount(): Long = droppedRecords
    // F42-D6: enqueue allocated a new SimpleDateFormat on every call.
    // One ThreadLocal formatter, like RuntimeLogger's.
    private val enqueueDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    @Volatile private var started = false
    @Synchronized
    // No65-D10: the Context parameter is gone — the sink never used it.
    // (no59 moved the sink to SplendorStorageRoot; the parameter stayed
    // only so the App.onCreate call was unchanged, and the compiler warned
    // about it.) Callers use LogMirror.init() now.
    fun init() {
        if (started) return
        // F42-D2: the probe below used to throw straight out of init(), and
        // App.onCreate calls init() unguarded - a MediaStore failure at
        // boot crashed the app before anything else ran. The probe only
        // validates writability; the writer thread opens the sink lazily
        // on the first record anyway, so a probe failure is non-fatal.
        try {
            val initialSink = openSink()
            try { initialSink.first.close() } catch (_: Throwable) {}
        } catch (t: Throwable) {
            try { android.util.Log.w("LogMirror", "initial sink probe failed; writer will retry lazily", t) } catch (_: Throwable) {}
        }
        started = true
        Thread({ writerLoop() }, "F47-LogMirror").apply { isDaemon = true; start() }
    }

    fun mirror(tag: String, priority: Int, msg: String) {
        enqueue(tag, priority, msg, null)
    }

    fun mirrorCrashAndWait(tag: String, msg: String) {
        try {
            if (!started) return
            val completion = java.util.concurrent.CountDownLatch(1)
            enqueue(tag, android.util.Log.ERROR, msg, completion)
            completion.await(500L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Crash reporting must never replace the original uncaught exception.
        }
    }

    private fun enqueue(tag: String, priority: Int, msg: String, completion: java.util.concurrent.CountDownLatch?) {
        try {
            if (!started) {
                droppedRecords++
                return
            }
            val now = enqueueDateFormat.get()!!.format(Date())
            val level = when (priority) {
                2 -> "V"
                3 -> "D"
                4 -> "I"
                5 -> "W"
                6 -> "E"
                else -> "?"
            }
            val safeMessage = msg.replace("\r", "\\r").replace("\n", "\\n")
            val record = LogRecord("$now $level/$tag: $safeMessage\n", priority, completion)
            if (!queue.offer(record)) {
                if (completion != null) {
                    // F42-D5: the old code polled one record, counted its
                    // completion down, then ignored offer()'s return value -
                    // under a racing producer the record could still be
                    // dropped while the crash waiter sat out the full
                    // 500ms. Retry the make-room step, and only release
                    // the waiter once the record is placed or finally lost.
                    var placed = false
                    var attempts = 0
                    while (!placed && attempts < 8) {
                        val evicted = queue.poll() ?: break
                        evicted.completion?.countDown()
                        placed = queue.offer(record)
                        attempts++
                    }
                    if (!placed) {
                        droppedRecords++
                        completion.countDown()
                    }
                } else {
                    droppedRecords++
                    return
                }
            }
        } catch (_: Throwable) {
            droppedRecords++
            completion?.countDown()
            // Logging must never affect gesture dispatch, including during shutdown.
        }
    }

    /**
     * P4: drops queued ROUTINE log records (no crash waiter attached),
     * keeping every crash-waiter record, and returns the count + bytes
     * dropped. Dropped records are counted in droppedRecords, exactly
     * like any other lost record. Called only by
     * NonEssentialCacheEvictor under CRITICAL memory.
     */
    data class DroppedRoutine(val count: Long, val bytes: Long)

    fun dropQueuedRoutine(): DroppedRoutine {
        var count = 0L
        var bytes = 0L
        val keep = ArrayList<LogRecord>()
        while (true) {
            val record = queue.poll() ?: break
            if (record.completion != null) {
                keep.add(record)
            } else {
                count++
                bytes += record.line.toByteArray(Charsets.UTF_8).size
                droppedRecords++
            }
        }
        for (record in keep) {
            if (!queue.offer(record)) {
                // The queue refilled while draining; release the waiter
                // rather than losing the crash line silently.
                droppedRecords++
                record.completion?.countDown()
            }
        }
        return DroppedRoutine(count, bytes)
    }

    private fun writerLoop() {
        var output: OutputStream? = null
        var sinkFile: File? = null
        var byteCount = 0L
        // P2: bytes written since the last flush. Routine lines batch
        // (flush at 8 KB or after 1 s idle); ERROR+ / crash-waiter
        // lines flush immediately, exactly as before.
        var unflushedBytes = 0L
        while (!Thread.currentThread().isInterrupted) {
            // F42-D3: the interrupt catch below used to `return`, skipping
            // the output.close() after the loop and leaking the open sink.
            // It breaks instead so the trailing close always runs.
            val record: LogRecord?
            try {
                record = queue.poll(1L, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (record == null) {
                // P2: idle flush — a routine line waits at most ~1 s for disk.
                if (unflushedBytes > 0L) {
                    try { output?.flush() } catch (_: Throwable) {}
                    unflushedBytes = 0L
                }
                continue
            }
            try {
                val line = record.line
                if (output == null) {
                    val opened = openSink()
                    output = opened.first
                    sinkFile = opened.second
                    byteCount = opened.third
                }
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (byteCount + bytes.size > MAX_BYTES) {
                    output.flush()
                    output.close()
                    rotate(sinkFile)
                    val replacement = openSink()
                    output = replacement.first
                    sinkFile = replacement.second
                    byteCount = replacement.third
                    unflushedBytes = 0L
                }
                output.write(bytes)
                unflushedBytes += bytes.size
                // P2: flush immediately for ERROR+ / crash-waiter lines;
                // batch routine lines (8 KB threshold, 1 s idle flush).
                if (record.priority >= android.util.Log.ERROR || record.completion != null || unflushedBytes >= 8192L) {
                    output.flush()
                    unflushedBytes = 0L
                }
                byteCount += bytes.size
                record.completion?.countDown()
            } catch (_: Throwable) {
                try { output?.close() } catch (_: Throwable) {}
                output = null
                sinkFile = null
                byteCount = 0L
                unflushedBytes = 0L
                record.completion?.countDown()
                // Drop this record and retry the sink on the next queued record.
            }
        }
        try { output?.close() } catch (_: Throwable) {}
    }

    private data class OpenedSink(
        val first: OutputStream,
        val second: File?,
        val third: Long
    )

    // no59 ITEM 3: one path. The sink is the canonical root file
    // /sdcard/Splendor-Assist/stroke_log.txt — no MediaStore, no
    // Downloads collection, no getExternalFilesDir. SplendorStorageRoot
    // throws when storage is not ready; the writer loop treats that like
    // any other open failure and retries on the next queued record.
    private fun openSink(): OpenedSink {
        val file = SplendorStorageRoot.file(FILE_NAME)
        return OpenedSink(FileOutputStream(file, true), file, if (file.exists()) file.length() else 0L)
    }

    // no59 ITEM 3: rotation is the plain-file version of the old
    // MediaStore rotate (F42-D1: delete the stale old file first, then
    // rename the current file to old — exactly one of each, never two
    // stroke_log.txt entries). The MediaStore rename/delete calls are
    // gone with the sink.
    private fun rotate(file: File?) {
        val current = file ?: throw IllegalStateException("log file unavailable")
        val old = File(current.parentFile, OLD_FILE_NAME)
        if (old.exists() && !old.delete()) throw IllegalStateException("cannot remove old log")
        if (current.exists() && !current.renameTo(old)) throw IllegalStateException("cannot rotate log")
    }
}
