package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger



import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry


























object ActionWindowEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val POLL_MS: Long get() = 2000L
    private val HOLD_LOSS_PCT: Float get() = 10f
    private val GO_LOSS_PCT: Float get() = 2f
    private val HOLD_JITTER_MULT: Float get() = 2f

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
                    val tol = CarrierProfileEngine.jitterTolMs.toFloat()
                    val loss = PacketLossProbeEngine.lossPct
                    val next = when {
                        CongestionSentinelEngine.congested || loss > HOLD_LOSS_PCT ||
                            NetProbeEngine.jitter > tol * HOLD_JITTER_MULT -> "HOLD"
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
                    AdapterSignalBus.publishNet(verdict)
                    PerformanceTelemetryRegistry.publishActionWindow(verdict,
                        "rtt=" + NetProbeEngine.rtt.toInt() + " jit=" + NetProbeEngine.jitter.toInt() +
                        " loss=" + loss.toInt())
                } catch (t: Throwable) {
                    com.assistant.diagnostic.RuntimeLogger.log("NetWindow error: " + t, "NETWINDOW")
                }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
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

/* ========
CarrierProfileEngine
======== */
