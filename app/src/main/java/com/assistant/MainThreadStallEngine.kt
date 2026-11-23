package com.assistant

import android.os.Handler

import android.os.Looper

import com.assistant.diagnostic.RuntimeLogger
import java.util.Locale

object MainThreadStallEngine {

    // Q68-23 (batch 5b): NOT admin-tunable — the old comment claimed
    // "ADMIN-TUNABLE (defaults = original hard-coded values)", but these
    // are plain getters returning literals; no admin store is read anywhere
    // in this file. The values below are the original hard-coded values,
    // kept as-is. If an admin knob is ever wanted, it must be wired to the
    // admin store first — the comment must not claim it already is.
    private val CADENCE_MS: Long get() = 250L
    private val SPIKE_MS: Long get() = 80L
    private val ALPHA: Float get() = 0.25f
    private val REPORT_MS: Long get() = 10_000L

    @Volatile private var running = false
    @Volatile var avgLatenessMs = 0f; private set
    @Volatile var spikesPerMin = 0f; private set   // rolling
    @Volatile private var winSpikes = 0L
    @Volatile private var winMax = 0L
    @Volatile private var totalSpikes = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val probe = object : Runnable {
        @Volatile var expected = 0L
        override fun run() {
            if (!running) return
            val late = (System.currentTimeMillis() - expected).coerceAtLeast(0L)
            val a = ALPHA
            avgLatenessMs = if (avgLatenessMs == 0f) late.toFloat()
                            else avgLatenessMs * (1 - a) + late * a
            if (late >= SPIKE_MS) {
                winSpikes++; totalSpikes++
                if (late > winMax) winMax = late
            }
            val cad = if (CADENCE_MS > 0) CADENCE_MS else 1L
            expected = System.currentTimeMillis() + cad
            handler.postDelayed(this, cad)
        }
    }

    @Volatile private var reportThread: Thread? = null

    fun start() {
        if (running) return
        // Phase M (M-4): D18-class double-start — a previous report thread
        // still winding down must be dead before a new one starts, or two
        // reporters publish spikesPerMin and race to reset winSpikes/winMax.
        // Wait briefly; give up (no start) rather than run two reporters.
        // (Same pattern as LagVerdictEngine Phase L L-1; the restart is
        // real: AdapterRecoveryEngine relaunches PerformanceEngineService
        // in-process on a stale heartbeat, and onCreate re-ignites these
        // singleton objects.)
        val old = reportThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        val cad = if (CADENCE_MS > 0) CADENCE_MS else 1L
        probe.expected = System.currentTimeMillis() + cad
        handler.postDelayed(probe, cad)
        val t = Thread {
            while (running) {
                val windowMs = if (REPORT_MS > 0) REPORT_MS else 1L
                try { Thread.sleep(windowMs) } catch (_: Throwable) { return@Thread }
                val winMin = windowMs / 60000f
                spikesPerMin = if (winMin > 0f) winSpikes / winMin else 0f
                if (winSpikes > 0L) {
                    RuntimeLogger.log("stalls n=" + winSpikes + " max=" + winMax +
                        "ms avg=" + String.format(Locale.US, "%.0f", avgLatenessMs) +
                        "ms total=" + totalSpikes, "LAGSTALL")
                }
                winSpikes = 0L; winMax = 0L
            }
        }
        t.isDaemon = true; t.name = "lag-stall-report"; reportThread = t; t.start()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(probe)
        // Phase M (M-4): wake the sleeper so it exits now instead of up
        // to 10 s later (and cannot be resurrected by a quick restart).
        reportThread?.interrupt()
    }
}

// --- MERGED: ThermalPeekEngine.kt ---

// Q68-23 (batch 5b): the old footer claimed "V3 ADMIN-WIRED … the check
// rhythm is admin-tunable and re-read every cycle" — false. ThermalPeek's
// POLL_MS is likewise a literal (10_000L) with no admin store read
// anywhere in the file. The rhythm is a fixed constant, not an admin knob.

/**
 * One cheap system reading: the OS thermal status. If lag storms correlate
 * with SEVERE+ status, the enemy is heat throttling; if status stays
 * NONE/LIGHT through a storm, throttling is exonerated and scheduling
 * contention becomes prime suspect.
 */
