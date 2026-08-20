package com.assistant.adapter.input

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * OomAdaptiveThrottleEngine — HARDWORKING ELIMINATOR & BOOSTER for HyperOS OOM/Cgroup throttling.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles apps via cgroups and OOM adj adjustments during fast gameplay.
 * A 3-second polling delay causes up to 45 frames of input lag at 15fps.
 *
 * This engine now:
 * 1. Polls every 500ms for instant detection of HyperOS throttling.
 * 2. Conditionlessly forces THREAD_PRIORITY_URGENT_AUDIO (-19) every cycle to fight silent cgroup throttling.
 * 3. Triggers OOM_HOSTILE at adj >= 10 (ultra-aggressive threshold).
 * 4. Signals the Queen Bee (SmartAssist) to drop non-essential UI work when hostile.
 */
object OomAdaptiveThrottleEngine {

    private const val OOM_HOSTILE_THRESHOLD = 10
    private const val POLL_MS = 500L
    private const val LOG_COOLDOWN_MS = 10_000L

    @Volatile private var running = false
    @Volatile var oomScore = 0; private set
    @Volatile var oomHostile = false; private set
    @Volatile var reapplyCount = 0; private set

    private var lastHostileLogMs = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-oom-throttle"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("OomAdaptiveThrottleEngine started (Hardworking Eliminator Mode - 500ms cycle)", "INPUT")
    }

    fun stop() { running = false }

    private fun poll() {
        val adj = readOomAdj(Process.myPid())
        oomScore = adj
        val hostile = adj >= OOM_HOSTILE_THRESHOLD
        val wasHostile = oomHostile
        oomHostile = hostile

        // CONDITIONLESS ACTIVE MITIGATION:
        // HyperOS throttles via cgroups even if thread priority isn't explicitly reset.
        // Force URGENT_AUDIO (-19) every cycle to prevent silent CPU starvation.
        try {
            val cur = Process.getThreadPriority(Process.myTid())
            if (cur > Process.THREAD_PRIORITY_URGENT_AUDIO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                reapplyCount++
            }
        } catch (_: Throwable) {}

        if (hostile) {
            // Publish hostile signal so Queen Bee (SmartAssist) can drop non-essential UI work
            AdapterSignalBus.publishInput("OOM_HOSTILE", adj.toLong())

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
                RuntimeLogger.log("OOM_CLEAR: adj=$adj — throttle released", "INPUT")
                // Publish clear signal to restore full performance
                AdapterSignalBus.publishInput("OOM_CLEAR", adj.toLong())
            }
        }
    }

    private fun readOomAdj(pid: Int): Int = try {
        java.io.BufferedReader(java.io.FileReader("/proc/$pid/oom_score_adj"))
            .use { it.readLine()?.trim()?.toIntOrNull() ?: 0 }
    } catch (_: Throwable) { 0 }
}
