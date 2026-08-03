package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * THE OUTPUT OF THE WHOLE STACK: one verdict, refreshed every 2s.
 *   GO      - link clean: full-speed decisions are safe
 *   CAUTION - degraded: prefer shorter, safer actions
 *   HOLD    - spike/loss in progress: worst moment to commit a long play
 * Published for the main runtime to read non-blocking.
 */
object ActionWindowEngine {

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var sinceMs = 0L
    @Volatile private var flips = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val p = CarrierProfileEngine.current
                    val loss = PacketLossProbeEngine.lossPct
                    val next = when {
                        CongestionSentinelEngine.congested || loss > 10f ||
                            NetProbeEngine.jitter > p.jitterToleranceMs * 2 -> "HOLD"
                        NetProbeEngine.quality == "GOOD" && loss < 2f -> "GO"
                        else -> "CAUTION"
                    }
                    if (next != verdict) {
                        flips++
                        sinceMs = System.currentTimeMillis()
                        RuntimeLogger.log("WINDOW " + verdict + " -> " + next +
                            " (rtt=" + String.format("%.0f", NetProbeEngine.rtt) +
                            "ms jit=" + String.format("%.0f", NetProbeEngine.jitter) +
                            "ms loss=" + String.format("%.0f", loss) + "%)", "NETWINDOW")
                        verdict = next
                    }
                    PerformanceTelemetryRegistry.publishActionWindow(verdict,
                        "rtt=" + NetProbeEngine.rtt.toInt() + " jit=" + NetProbeEngine.jitter.toInt() +
                        " loss=" + loss.toInt())
                } catch (_: Throwable) { }
                try { Thread.sleep(2000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-window"; t.start()
    }

    fun stop() { running = false }

    fun state(): String {
        val held = if (sinceMs > 0) (System.currentTimeMillis() - sinceMs) / 1000L else 0L
        return verdict + "(" + held + "s)"
    }
}
