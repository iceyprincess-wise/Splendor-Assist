package com.assistant






















import android.os.Handler


import android.os.Looper










import com.assistant.diagnostic.RuntimeLogger






























object MainThreadStallEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
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

    fun start() {
        if (running) return
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
                        "ms avg=" + String.format("%.0f", avgLatenessMs) +
                        "ms total=" + totalSpikes, "LAGSTALL")
                }
                winSpikes = 0L; winMax = 0L
            }
        }
        t.isDaemon = true; t.name = "lag-stall-report"; t.start()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(probe)
    }
}

// --- MERGED: ThermalPeekEngine.kt ---

// V3 ADMIN-WIRED - thermal evidence probe, live rhythm

/**
 * One cheap system reading: the OS thermal status. If lag storms correlate
 * with SEVERE+ status, the enemy is heat throttling; if status stays
 * NONE/LIGHT through a storm, throttling is exonerated and scheduling
 * contention becomes prime suspect. V3: the check rhythm is admin-tunable
 * and re-read every cycle.
 */
