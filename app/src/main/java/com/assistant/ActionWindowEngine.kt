package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger



import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry


























object ActionWindowEngine {

    // 6a: NOT admin-tunable — these are plain literals; no admin store
    // is read anywhere (same false-claim class as Q68-23, fixed in 5b).
    // Values are the original hard-coded ones, kept as-is.
    private val POLL_MS: Long get() = 2000L
    private val HOLD_LOSS_PCT: Float get() = 10f
    private val GO_LOSS_PCT: Float get() = 2f
    private val HOLD_JITTER_MULT: Float get() = 2f

    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    @Volatile var verdict = "UNKNOWN"; private set
    // 6a: `sinceMs` removed with state() — it only fed state()'s held-seconds.
    // 6a: `flips` removed — write-only (incremented, never read); `state()`
    // below is removed with it (zero callers repo-wide; the live reader
    // is ConnectionHealEngine on `verdict`).

    fun start() {
        if (running) return; running = true
        // Task 5 (gate): D18 double-start fix, R-1/R-3 shape (GcStall T-14).
        // stop() used to be bare `running = false` with no stored thread
        // and no interrupt: a restart inside the poll sleep left the old
        // poller alive beside the new one (both looping forever, since the
        // old thread wakes to running == true again). Join the old thread
        // 500 ms and give up - with running left true the old poller keeps
        // the engine alive on its own, so start() is a safe no-op.
        val old = pollThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
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
        pollThread = t
    }

    fun stop() { running = false; pollThread?.interrupt() }
}

/* ========
CarrierProfileEngine
======== */
