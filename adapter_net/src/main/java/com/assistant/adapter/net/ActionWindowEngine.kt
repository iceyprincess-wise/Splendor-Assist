package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.admin.AdminConfigStore
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

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val POLL_MS: Long get() = AdminConfigStore.getLong("net.window.poll_ms", 2000L)
    private val HOLD_LOSS_PCT: Float get() = AdminConfigStore.get("net.window.hold_loss_pct", 10f)
    private val GO_LOSS_PCT: Float get() = AdminConfigStore.get("net.window.go_loss_pct", 2f)
    private val HOLD_JITTER_MULT: Float get() = AdminConfigStore.get("net.window.hold_jitter_mult", 2f)

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
                        CongestionSentinelEngine.congested || loss > HOLD_LOSS_PCT ||
                            NetProbeEngine.jitter > p.jitterToleranceMs * HOLD_JITTER_MULT -> "HOLD"
                        NetProbeEngine.quality == "GOOD" && loss < GO_LOSS_PCT -> "GO"
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
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
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
