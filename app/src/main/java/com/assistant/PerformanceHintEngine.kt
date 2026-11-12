package com.assistant







import android.content.Context













import android.os.Build






import android.os.PerformanceHintManager

import android.os.Process




































object PerformanceHintEngine {
    @Volatile private var captureActive = false
    private var session: PerformanceHintManager.Session? = null
    private var sessionThreadId = -1
    private var sessionTargetIntervalNanos = -1L
    private val lock = Any()

    /** Called for each new capture lifetime; a session is created lazily on the vision worker. */
    fun beginCapture() = synchronized(lock) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { session?.close() } catch (_: Throwable) {}
        }
        session = null
        sessionThreadId = -1
        sessionTargetIntervalNanos = -1L
        captureActive = true
    }

    fun endCapture() = synchronized(lock) {
        captureActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { session?.close() } catch (_: Throwable) {}
        }
        session = null
        sessionThreadId = -1
        sessionTargetIntervalNanos = -1L
    }

    fun reportActualWorkload(context: Context, actualDurationNanos: Long, targetFps: Int) {
        if (!captureActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetIntervalNanos = 1_000_000_000L / targetFps.coerceIn(30, 60)
        synchronized(lock) {
            if (!captureActive) return
            val tid = Process.myTid()
            if (session == null || sessionThreadId != tid || sessionTargetIntervalNanos != targetIntervalNanos) {
                try { session?.close() } catch (_: Throwable) {}
                val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
                session = try { manager.createHintSession(intArrayOf(tid), targetIntervalNanos) } catch (_: Throwable) { null }
                sessionThreadId = if (session == null) -1 else tid
                sessionTargetIntervalNanos = if (session == null) -1L else targetIntervalNanos
            }
            try { session?.reportActualWorkDuration(actualDurationNanos.coerceAtLeast(1L)) } catch (_: Throwable) {
                try { session?.close() } catch (_: Throwable) {}
                session = null
                sessionThreadId = -1
                sessionTargetIntervalNanos = -1L
            }
        }
    }
}

/* ========
RehydratedStateSnapshot
======== */
