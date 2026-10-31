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
    // Tags explicitly discovered in the pinned engine sources. RuntimeLogger hook captures every tag, not only this registry.
    private val MIRRORED_TAGS = setOf("SPLENDOR", "EXEC", "ROUTED", "NativeBridge", "HybridExecution", "InputLatency", "GestureQueue", "LAGFRAME", "LAGSTALL", "STUTTER", "LAG_BEE", "LAGVERDICT", "NETPROBE", "MEMBUSENGINE", "LAGTHERM", "NETLOSS", "NETSPIKE", "LAGGC", "LAGRENDER", "INPUT", "HEALTH", "WATCHDOG", "ADAPTER", "ENGINE", "ENGINE_FAULT", "F47FPS", "F47NET", "F47MEM", "F47THRM", "F47CRASH")
    private data class LogRecord(val line: String, val completion: java.util.concurrent.CountDownLatch? = null)
    private val queue = LinkedBlockingQueue<LogRecord>(QUEUE_CAPACITY)
    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Synchronized
    fun init(context: Context) {
        if (started) return
        appContext = context.applicationContext
        val initialSink = openSink(context.applicationContext)
        try { initialSink.first.close() } catch (_: Throwable) {}
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
            if (!started) return
            val now = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
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
                    queue.poll()?.completion?.countDown()
                    queue.offer(record)
                } else return
            }
        } catch (_: Throwable) {
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
            val record = try { queue.poll(1L, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: continue
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
                    output?.flush()
                    output?.close()
                    output = null
                    rotate(context, sinkUri, sinkFile)
                    sinkUri = null
                    val replacement = openSink(context)
                    output = replacement.first
                    sinkUri = replacement.second
                    sinkFile = replacement.third
                    byteCount = replacement.fourth
                }
                output!!.write(bytes)
                output!!.flush()
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
            if (old != null) {
                if (resolver.update(old, oldValues, null, null) <= 0) throw IllegalStateException("MediaStore old-log rename failed")
            } else if (resolver.update(uri, oldValues, null, null) <= 0) {
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
