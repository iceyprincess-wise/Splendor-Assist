package com.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.assistant.storage.SplendorStorageRoot
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/** Fixed-size, primitive-only breadcrumbs and low-rate watchdog for crash forensics. */
object CrashForensics {
    private const val CAPACITY = 200
    private const val WATCHDOG_MS = 15000L
    private const val SNAPSHOT_MS = 10000L
    private const val CAPTURE = 1
    private const val VISION = 2
    private const val ENGINE = 3
    private const val OVERLAY = 4
    private const val REPORT_NAME = "SplendorHealLog.txt"
    private val lock = Any()
    private val times = LongArray(CAPACITY)
    private val stages = IntArray(CAPACITY)
    private val details = LongArray(CAPACITY)
    // Cache source location once per stage to keep hot breadcrumb writes cheap.
    private val locations = Array(CAPACITY) { "unknown:0" }
    private val stageLocations = arrayOfNulls<String>(256)
    private val beats = LongArray(5)
    private val reported = LongArray(5)
    private val cursor = AtomicLong(0L)
    @Volatile private var captureWidth = 0
    @Volatile private var captureHeight = 0
    @Volatile private var engineStage = 0
    @Volatile private var installed = false
    private val handler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            try {
                checkStalls(System.currentTimeMillis())
                flushSnapshot(false)
            } catch (_: Throwable) { }
            handler.postDelayed(this, SNAPSHOT_MS)
        }
    }

    /** Hot-path safe: writes primitive values into fixed arrays, with no per-frame objects. */
    @JvmStatic
    fun mark(stage: Int, loop: Int, detail: Long = 0L) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val n = cursor.getAndIncrement()
            val slot = (n % CAPACITY).toInt()
            times[slot] = now
            stages[slot] = stage
            details[slot] = detail
            val location = if (stage in stageLocations.indices) stageLocations[stage] else null
            locations[slot] = location ?: sourceFromCurrentStack().also { captured ->
                if (stage in stageLocations.indices) stageLocations[stage] = captured
            }
            if (loop in CAPTURE..OVERLAY) beats[loop] = now
        }
    }

    @JvmStatic
    fun setCaptureSize(width: Int, height: Int) {
        captureWidth = width
        captureHeight = height
    }

    @JvmStatic
    fun setEngineStage(stage: Int) {
        engineStage = stage
    }

    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        installed = true
        handler.removeCallbacks(monitor)
        handler.post(monitor)
        mark(1, OVERLAY, android.os.Process.myPid().toLong())
    }

    /** Called before the platform handler, while the VM can still write a report. */
    @JvmStatic
    fun onUncaught(thread: Thread, error: Throwable) {
        try {
            val trace = java.io.StringWriter()
            error.printStackTrace(java.io.PrintWriter(trace))
            appendReport("[DEATH] uncaught thread=${thread.name} id=${thread.id} " +
                "${error.javaClass.name}: ${error.message} throwSite=${throwLocation(error)}\n" +
                "capture=${captureWidth}x${captureHeight} engineStage=${stageName(engineStage)}\n" +
                trace.toString() + snapshotText())
        } catch (_: Throwable) { }
    }

    /** Resolve JVM frames to an app/core repository-relative path and source line. */
    private fun frameLocation(frame: StackTraceElement): String {
        val file = frame.fileName ?: "Unknown.kt"
        val className = frame.className.substringBefore('$')
        val pkg = className.substringBeforeLast('.', "").replace('.', '/')
        val root = if (className.startsWith("com.assistant.diagnostic."))
            "core/src/main/java" else "app/src/main/java"
        return "$root/$pkg/$file:${frame.lineNumber}"
    }

    private fun sourceFromCurrentStack(): String {
        val frame = Thread.currentThread().stackTrace.firstOrNull {
            it.className != Thread::class.java.name &&
                it.className != CrashForensics::class.java.name &&
                !it.className.startsWith("java.lang.Thread")
        }
        return if (frame == null) "unknown:0" else frameLocation(frame)
    }

    @JvmStatic
    fun currentLocation(): String = sourceFromCurrentStack()

    @JvmStatic
    fun throwLocation(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.lineNumber > 0 }
        return if (frame == null) "unknown:0" else frameLocation(frame)
    }

    /** JNI callback for the first native vision frame only. */
    @JvmStatic
    fun nativeBreadcrumb(source: String, line: Int, phase: String) {
        try {
            mark(4, VISION, line.toLong())
            val relative = if (source.contains("/app/src/main/cpp/"))
                source.substringAfter("/app/src/main/cpp/") else source.substringAfterLast('/')
            synchronized(lock) {
                val slot = ((cursor.get() - 1L) % CAPACITY).toInt()
                locations[slot] = "app/src/main/cpp/$relative:$line native=$phase"
            }
        } catch (_: Throwable) { }
    }

    /** A restarted survivor records the OS death verdict; previous snapshots remain in this log. */
    @JvmStatic
    fun reportDetectedDeath(process: String, pid: String, verdict: String) {
        try {
            appendReport("[DEATH DETECTED] process=$process pid=$pid verdict=$verdict source=${sourceFromCurrentStack()} " +
                "capture=${captureWidth}x$captureHeight engineStage=${stageName(engineStage)}\n" + snapshotText())
        } catch (_: Throwable) { }
    }

    private fun checkStalls(now: Long) {
        val labels = arrayOf("", "CAPTURE", "VISION", "ENGINE", "OVERLAY")
        for (loop in CAPTURE..OVERLAY) {
            val beat: Long
            synchronized(lock) { beat = beats[loop] }
            if (beat > 0L && now - beat > WATCHDOG_MS && reported[loop] != beat) {
                reported[loop] = beat
                appendReport("[STALLED] loop=${labels[loop]} heartbeatAgeMs=${now - beat} source=${sourceFromCurrentStack()} " +
                    "thresholdMs=$WATCHDOG_MS capture=${captureWidth}x$captureHeight " +
                    "engineStage=${stageName(engineStage)} action=report-only\n" + snapshotText())
            }
        }
    }

    /** Snapshot formatting and disk I/O happen only on the watchdog/crash path. */
    private fun snapshotText(): String {
        val out = StringBuilder(4096)
        synchronized(lock) {
            val end = cursor.get()
            val start = (end - CAPACITY).coerceAtLeast(0L)
            out.append("--- BREADCRUMBS newest-first-window count=").append(end - start).append(" ---\n")
            var n = start
            while (n < end) {
                val slot = (n % CAPACITY).toInt()
                val time = times[slot]
                if (time > 0L) out.append(time).append(" ").append(stageName(stages[slot]))
                    .append(" source=").append(locations[slot])
                    .append(" detail=").append(details[slot]).append('\n')
                n++
            }
        }
        out.append("--- LOOP HEARTBEATS ---\n")
        synchronized(lock) {
            out.append("capture=").append(beats[CAPTURE]).append(" vision=").append(beats[VISION])
                .append(" engine=").append(beats[ENGINE]).append(" overlay=").append(beats[OVERLAY]).append('\n')
        }
        return out.toString()
    }

    private fun flushSnapshot(full: Boolean) {
        val header = if (full) "[SNAPSHOT] full ring source=${sourceFromCurrentStack()}\n"
            else "[SNAPSHOT] periodic source=${sourceFromCurrentStack()}\n"
        appendReport(header + snapshotText())
    }

    private fun appendReport(text: String) {
        val file = try { SplendorStorageRoot.file(REPORT_NAME) } catch (_: Throwable) { return }
        try {
            FileOutputStream(file, true).use { stream ->
                stream.write(("\n" + text).toByteArray(Charsets.UTF_8))
                stream.flush()
                try { stream.fd.sync() } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    private fun stageName(stage: Int): String = when (stage) {
        1 -> "OVERLAY_START"
        2 -> "CAPTURE_IMAGE"
        3 -> "VISION_NORMALIZE"
        4 -> "VISION_CORE"
        5 -> "ENGINE_FRAME_ASSEMBLY"
        6 -> "ENGINE_DECISION_LOOP"
        7 -> "CAPTURE_SIZE"
        else -> "STAGE_$stage"
    }
}
