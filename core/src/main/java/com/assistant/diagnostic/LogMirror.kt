package com.assistant.diagnostic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Temporary, best-effort app log mirror. Producer calls only enqueue bounded strings. */
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
    private data class LogRecord(val line: String, val completion: java.util.concurrent.CountDownLatch? = null)
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
    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Synchronized
    fun init(context: Context) {
        if (started) return
        appContext = context.applicationContext
        // F42-D2: the probe below used to throw straight out of init(), and
        // App.onCreate calls init() unguarded - a MediaStore failure at
        // boot crashed the app before anything else ran. The probe only
        // validates writability; the writer thread opens the sink lazily
        // on the first record anyway, so a probe failure is non-fatal.
        try {
            val initialSink = openSink(context.applicationContext)
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
            val record = LogRecord("$now $level/$tag: $safeMessage\n", completion)
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

    private fun writerLoop() {
        val context = appContext ?: return
        var output: OutputStream? = null
        var sinkUri: Uri? = null
        var sinkFile: File? = null
        var byteCount = 0L
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
            if (record == null) continue
            try {
                val line = record.line
                if (output == null) {
                    val opened = openSink(context)
                    output = opened.first
                    sinkUri = opened.second
                    sinkFile = opened.third
                    byteCount = opened.fourth
                }
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (byteCount + bytes.size > MAX_BYTES) {
                    output.flush()
                    output.close()
                    rotate(context, sinkUri, sinkFile)
                    val replacement = openSink(context)
                    output = replacement.first
                    sinkUri = replacement.second
                    sinkFile = replacement.third
                    byteCount = replacement.fourth
                }
                output.write(bytes)
                output.flush()
                byteCount += bytes.size
                record.completion?.countDown()
            } catch (_: Throwable) {
                try { output?.close() } catch (_: Throwable) {}
                output = null
                sinkUri = null
                sinkFile = null
                byteCount = 0L
                record.completion?.countDown()
                // Drop this record and retry the sink on the next queued record.
            }
        }
        try { output?.close() } catch (_: Throwable) {}
    }

    private data class OpenedSink(
        val first: OutputStream,
        val second: Uri?,
        val third: File?,
        val fourth: Long
    )

    private fun openSink(context: Context): OpenedSink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = findDownload(context, FILE_NAME) ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
            }
            val size = querySize(context, uri)
            val stream = resolver.openOutputStream(uri, if (size == 0L) "w" else "wa") ?: throw IllegalStateException("MediaStore open failed")
            return OpenedSink(stream, uri, null, size)
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("external files directory unavailable")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create log directory")
        val file = File(dir, FILE_NAME)
        return OpenedSink(FileOutputStream(file, true), null, file, if (file.exists()) file.length() else 0L)
    }

    private fun rotate(context: Context, uri: Uri?, file: File?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            val resolver = context.contentResolver
            val old = findDownload(context, OLD_FILE_NAME)
            val oldValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, OLD_FILE_NAME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            // F42-D1: when the old file already existed, the old code
            // updated OLD with its own name (a no-op) and never renamed
            // the current uri to old - then inserted a NEW file named
            // stroke_log.txt. Result: TWO stroke_log.txt entries, and
            // findDownload (no ORDER BY) returns an arbitrary one, so
            // openSink could keep appending to the rotated-away file or
            // the new one unpredictably. Delete the stale old file first,
            // then rename the current uri to old - exactly one of each.
            if (old != null) {
                if (resolver.delete(old, null, null) <= 0) throw IllegalStateException("MediaStore old-log delete failed")
            }
            if (resolver.update(uri, oldValues, null, null) <= 0) {
                throw IllegalStateException("MediaStore rotate failed")
            }
            val replacement = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), replacement) == null) {
                throw IllegalStateException("MediaStore replacement insert failed")
            }
            return
        }
        val current = file ?: throw IllegalStateException("log file unavailable")
        val old = File(current.parentFile, OLD_FILE_NAME)
        if (old.exists() && !old.delete()) throw IllegalStateException("cannot remove old log")
        if (current.exists() && !current.renameTo(old)) throw IllegalStateException("cannot rotate log")
    }

    private fun findDownload(context: Context, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
        }
        return null
    }

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }
}
