package com.assistant.adapter.lag

// V2.1 PROACTIVE - zero main-thread I/O
import android.os.Handler
import android.os.Looper
import com.assistant.diagnostic.RuntimeLogger

/**
 * V2.1: the probe on the main looper now ONLY measures - no logging, no file
 * I/O on the thread under test (the old version wrote a log line during the
 * very stalls it was measuring). A background reporter summarizes every 10s,
 * one line, only when stalls actually happened.
 */
object MainThreadStallEngine {

    private const val CADENCE_MS = 250L
    private const val SPIKE_MS = 80L
    private const val ALPHA = 0.25f
    private const val REPORT_MS = 10_000L

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
            avgLatenessMs = if (avgLatenessMs == 0f) late.toFloat()
                            else avgLatenessMs * (1 - ALPHA) + late * ALPHA
            if (late >= SPIKE_MS) {
                winSpikes++; totalSpikes++
                if (late > winMax) winMax = late
            }
            expected = System.currentTimeMillis() + CADENCE_MS
            handler.postDelayed(this, CADENCE_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        probe.expected = System.currentTimeMillis() + CADENCE_MS
        handler.postDelayed(probe, CADENCE_MS)
        val t = Thread {
            while (running) {
                try { Thread.sleep(REPORT_MS) } catch (_: Throwable) { return@Thread }
                val winMin = REPORT_MS / 60000f
                spikesPerMin = winSpikes / winMin
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
