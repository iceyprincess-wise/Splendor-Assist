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
    // No68-C-D21: a session-create failure used to leave
    // sessionTargetIntervalNanos at -1, so every following frame retried
    // createHintSession and threw again — a per-frame exception on the
    // vision path. Back off 5 s after a failure.
    @Volatile private var lastSessionFailureMs = 0L
    // No68-C-D22: a null PerformanceHintManager used to return silently on
    // every frame, so a device without the service was unobservable. Log once.
    @Volatile private var managerMissingLogged = false

    // No68-E-D45: a failed hint session (manager null, createHintSession
    // throws, report throws) used to vanish silently — no counter, no
    // log, no snapshot — so a device where ADPF never engages looks
    // identical to one where it works. Count failures and expose them.
    @Volatile private var failedAttempts = 0L

    /** Diagnostics snapshot: is a hint session live, and how often has it failed? */
    fun hintRuntimeSnapshot(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "captureActive" to captureActive,
            "sessionActive" to (session != null),
            "sessionThreadId" to sessionThreadId,
            "sessionTargetIntervalNanos" to sessionTargetIntervalNanos,
            "failedAttempts" to failedAttempts
        )
    }

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
        // No68-E-D46: the old coerceIn(30, 60) silently re-mapped any
        // slower target (e.g. a 15fps brake target) to 30fps, telling
        // ADPF the thread must finish every 33ms when it really has
        // 66ms — over-boosting the CPU for work that is not late. The
        // interval now follows the real target down to 15fps.
        // (No behaviour change today: setTargetFps only admits 30/45/60.)
        val targetIntervalNanos = 1_000_000_000L / targetFps.coerceIn(15, 60)
        synchronized(lock) {
            if (!captureActive) return
            val tid = Process.myTid()
            if (session == null || sessionThreadId != tid || sessionTargetIntervalNanos != targetIntervalNanos) {
                if (System.currentTimeMillis() - lastSessionFailureMs < 5_000L) return
                try { session?.close() } catch (_: Throwable) {}
                val manager = context.getSystemService(PerformanceHintManager::class.java)
                    ?: run {
                        failedAttempts++
                        if (!managerMissingLogged) {
                            managerMissingLogged = true
                            android.util.Log.w("PerformanceHintEngine", "PerformanceHintManager unavailable; hint sessions disabled")
                        }
                        return
                    }
                session = try { manager.createHintSession(intArrayOf(tid), targetIntervalNanos) } catch (_: Throwable) {
                    lastSessionFailureMs = System.currentTimeMillis()
                    failedAttempts++
                    null
                }
                sessionThreadId = if (session == null) -1 else tid
                sessionTargetIntervalNanos = if (session == null) -1L else targetIntervalNanos
            }
            try { session?.reportActualWorkDuration(actualDurationNanos.coerceAtLeast(1L)) } catch (_: Throwable) {
                lastSessionFailureMs = System.currentTimeMillis()
                failedAttempts++
                try { session?.close() } catch (_: Throwable) {}
                session = null
                sessionThreadId = -1
                sessionTargetIntervalNanos = -1L
            }
        }
    }
}
