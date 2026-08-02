package com.assistant.adapter.net

import com.assistant.diagnostic.RuntimeLogger

/**
 * Watches the probe stream for congestion onset: jitter climbing faster than
 * RTT means queueing is starting BEFORE lag is felt. Publishes an early-warning
 * window so the runtime can prefer safe actions while the network wobbles.
 */
object CongestionSentinelEngine {

    @Volatile private var running = false
    @Volatile var congested = false; private set
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
                    val rising = j > lastJitter * 1.5f && j > p.jitterToleranceMs * 0.6f
                    val over = j > p.jitterToleranceMs
                    val was = congested
                    congested = rising || over
                    if (congested && !was) {
                        warnings++
                        RuntimeLogger.log("CONGESTION ONSET jitter=" + String.format("%.0f", j) +
                            "ms tolerance=" + p.jitterToleranceMs + "ms warnings=" + warnings, "NET")
                    }
                    lastJitter = j
                } catch (_: Throwable) { }
                try { Thread.sleep(3000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-sentinel"; t.start()
    }

    fun stop() { running = false }
}
