package com.assistant.adapter.lag

// V3.1 - no flapping
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.admin.AdminConfigStore
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * V3.1: ANY level change (including LIGHT<->HEAVY, which V3 let flap five
 * times in 21s) now needs the target wanted on consecutive polls AND at
 * least 8s since the last change. Escalation confirms in 2 polls, release
 * to NONE in 5 - shedding stays quick to arm, slow to thrash.
 */
object LoadShedGovernor {

    private val MIN_HOLD_MS get() = AdminConfigStore.getMs("shed_min_hold_ms")

    @Volatile private var running = false
    @Volatile var level = "NONE"; private set
    @Volatile private var candidate = "NONE"
    @Volatile private var streak = 0
    @Volatile private var lastChangeMs = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    // FAST PATH: a SEIZURE burst escalates immediately -
                    // sub-second truth beats the 20s window when a freeze hits
                    val burst = PerformanceTelemetryRegistry.currentStutterState()
                    val want = if (burst == "SEIZURE") "HEAVY" else when (LagVerdictEngine.verdict) {
                        "CHOKING" -> "HEAVY"
                        "JITTERY" -> "LIGHT"
                        else -> "NONE"
                    }
                    if (want == candidate) streak++ else { candidate = want; streak = 1 }
                    val need = if (candidate == "NONE") 5 else 2
                    val now = System.currentTimeMillis()
                    if (candidate != level && streak >= need &&
                        (lastChangeMs == 0L || now - lastChangeMs >= MIN_HOLD_MS)) {
                        RuntimeLogger.log("LOAD SHED " + level + " -> " + candidate +
                            " (device=" + LagVerdictEngine.verdict + ")", "LOADSHED")
                        level = candidate
                        lastChangeMs = now
                    }
                    PerformanceTelemetryRegistry.publishLoadShed(level)
                } catch (_: Throwable) { }
                try { Thread.sleep(2000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-loadshed"; t.start()
    }

    fun stop() { running = false }
}
