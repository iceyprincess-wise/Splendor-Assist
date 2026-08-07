package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.admin.AdminConfigStore

/**
 * V2: reports BOTH edges. Onset warns before lag is felt; recovery (with how
 * long the dirty window lasted) tells the runtime when aggression is safe again.
 */
object CongestionSentinelEngine {

    @Volatile private var running = false
    @Volatile var congested = false; private set
    @Volatile var congestedSinceMs = 0L; private set
    @Volatile private var lastJitter = 0f
    @Volatile private var warnings = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val j = NetProbeEngine.jitter
                    val p = CarrierProfileEngine.current
                    val riseFactor = AdminConfigStore.get("sentinel_rise_factor")
                    val riseFraction = AdminConfigStore.get("sentinel_rise_fraction")
                    val rising = j > lastJitter * riseFactor && j > p.jitterToleranceMs * riseFraction
                    val over = j > p.jitterToleranceMs
                    val now = rising || over
                    if (now && !congested) {
                        congestedSinceMs = System.currentTimeMillis()
                        warnings++
                        RuntimeLogger.log("CONGESTION ONSET jitter=" + String.format("%.0f", j) +
                            "ms tol=" + p.jitterToleranceMs + "ms n=" + warnings, "NET")
                    } else if (!now && congested) {
                        val dur = (System.currentTimeMillis() - congestedSinceMs) / 1000L
                        RuntimeLogger.log("CONGESTION CLEARED after " + dur + "s jitter=" +
                            String.format("%.0f", j) + "ms", "NET")
                    }
                    congested = now
                    lastJitter = j
                } catch (_: Throwable) { }
                try { Thread.sleep(AdminConfigStore.getMs("sentinel_cadence_ms")) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-sentinel"; t.start()
    }

    fun stop() { running = false }
}
