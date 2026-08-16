package com.assistant.adapter.input

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * OomAdaptiveThrottleEngine — HyperOS OOM throttle responder.
 *
 * TouchQualityEngine detects OOM adj=200 but only logs it — the bus
 * carries no OOM signal, so no engine downstream can react.
 *
 * This engine:
 * 1. Reads OOM score every 3s (faster than TouchQuality's 5s cycle).
 * 2. Publishes an OOM_HOSTILE / OOM_OK input classification override
 *    to AdapterSignalBus so downstream contributors can read it.
 * 3. Immediately reapplies URGENT_DISPLAY priority when OOM rises
 *    (InputPriorityEngine reapplies only every 30s — too slow for
 *    HyperOS which can re-throttle within seconds).
 * 4. On OOM_HOSTILE, logs a THROTTLED_HOSTILE event once per 60s so
 *    the heal log captures the persistent throttle state.
 *
 * OOM adj interpretation on Android:
 *   0         = foreground, not throttleable
 *   100-200   = visible/perceptible tier — HyperOS WILL throttle at 200
 *   500+      = background, killable
 *
 * OOM_HOSTILE threshold: adj >= 100 (we are being throttled)
 * OOM_OK threshold: adj < 100 (we have foreground scheduling)
 */
object OomAdaptiveThrottleEngine {

    private const val OOM_HOSTILE_THRESHOLD = 100
    private const val POLL_MS = 3_000L
    private const val LOG_COOLDOWN_MS = 60_000L

    @Volatile private var running = false
    @Volatile var oomScore = 0; private set
    @Volatile var oomHostile = false; private set
    @Volatile var reapplyCount = 0; private set

    private var lastHostileLogMs = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-oom-throttle"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("OomAdaptiveThrottleEngine started", "INPUT")
    }

    fun stop() { running = false }

    private fun poll() {
        val adj = readOomAdj(Process.myPid())
        oomScore = adj

        val hostile = adj >= OOM_HOSTILE_THRESHOLD
        val wasHostile = oomHostile
        oomHostile = hostile

        if (hostile) {
            // Immediately reapply priority — HyperOS resets it actively.
            // This engine polls 10× faster than InputPriorityEngine.
            try {
                val cur = Process.getThreadPriority(Process.myTid())
                if (cur > Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    reapplyCount++
                }
            } catch (_: Throwable) {}

            // Publish hostile input classification so bus consumers know
            AdapterSignalBus.publishInput("OOM_HOSTILE", InputLatencyEngine.latencyMs)

            val now = System.currentTimeMillis()
            if (!wasHostile || now - lastHostileLogMs > LOG_COOLDOWN_MS) {
                lastHostileLogMs = now
                RuntimeLogger.log(
                    "OOM_HOSTILE: adj=$adj — HyperOS throttling active. " +
                        "Priority reapply count=$reapplyCount",
                    "INPUT"
                )
            }
        } else {
            if (wasHostile) {
                RuntimeLogger.log("OOM_OK: adj=$adj — throttle released", "INPUT")
            }
            // Restore normal classification
            AdapterSignalBus.publishInput(
                InputLatencyEngine.classification,
                InputLatencyEngine.latencyMs
            )
        }
    }

    private fun readOomAdj(pid: Int): Int = try {
        java.io.BufferedReader(java.io.FileReader("/proc/$pid/oom_score_adj"))
            .use { it.readLine()?.trim()?.toIntOrNull() ?: 0 }
    } catch (_: Throwable) { 0 }
}
