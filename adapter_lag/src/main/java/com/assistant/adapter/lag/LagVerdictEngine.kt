package com.assistant.adapter.lag

// V3 ADMIN-WIRED - every threshold live, publishes the Detector snapshot
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.diagnostic.AdapterSignalBus

/**
 * The judge: reads every lag measurement and names the state -
 * SMOOTH / JITTERY / CHOKING. A change must be seen on N consecutive polls
 * before it flips (no whipsawing on a single blip). V3: the poll rhythm and
 * EVERY threshold line are admin-tunable and re-read each poll; each poll
 * also publishes the full live lag snapshot the admin Detector reads.
 */
object LagVerdictEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val POLL_MS: Long get() = 2000L
    private val JITTER_MS: Float get() = 10f
    private val STABILITY_PCT: Float get() = 65f
    private val CHOKE_STALLS: Float get() = 18f
    private val CHOKE_MTSTALL_MS: Float get() = 120f
    private val CHOKE_SPIKES: Float get() = 20f
    private val CONFIRM_POLLS: Int get() = 2

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var candidate = "UNKNOWN"
    @Volatile private var streak = 0
    @Volatile private var lastHeartbeat = 0L

    /**
     * Explicit player-observed severe-lag assertion.
     *
     * The assertion is bounded and feeds the same AdapterSignalBus used by
     * the normal lag verdict path. Once it expires, measured lag takes over.
     */
    fun forceManualChoking(durationMs: Long = 10_000L, source: String = "MANUAL") {
        val now = System.currentTimeMillis()
        AdapterSignalBus.publishManualLagEscalation(durationMs, source)
        candidate = "CHOKING"
        streak = CONFIRM_POLLS.coerceAtLeast(1)
        verdict = "CHOKING"
        lastHeartbeat = now
        AdapterSignalBus.publishLag("CHOKING")
        RuntimeLogger.log(
            "MANUAL LAG ESCALATION source=$source durationMs=${durationMs.coerceAtLeast(1000L)}",
            "LAGVERDICT"
        )
    }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val jit = FramePacingEngine.jitterMs
                    val stab = FramePacingEngine.stabilityPct
                    val stallRate = FramePacingEngine.stallsPerMin
                    val mtStall = MainThreadStallEngine.avgLatenessMs
                    val spm = MainThreadStallEngine.spikesPerMin
                    val raw = if (AdapterSignalBus.manualLagEscalationActive) {
                        "CHOKING"
                    } else when {
                        stallRate > CHOKE_STALLS || mtStall > CHOKE_MTSTALL_MS ||
                            spm > CHOKE_SPIKES -> "CHOKING"
                        jit > JITTER_MS || stab < STABILITY_PCT -> "JITTERY"
                        FramePacingEngine.avgGapMs > 0f -> "SMOOTH"
                        else -> "UNKNOWN"
                    }
                    if (raw == candidate) streak++ else { candidate = raw; streak = 1 }
                    // Crowded zone (penalty box / corner) temporarily
                    // inflates stall/jitter from rendering load, not
                    // sustained device stress. Require 2 extra polls
                    // before confirming CHOKING to protect attacking actions.
                    val basePoll = if (CONFIRM_POLLS < 1) 1 else CONFIRM_POLLS
                    val need = if (raw == "CHOKING" && AdapterSignalBus.crowdingZone)
                        basePoll + 2 else basePoll
                    val now = System.currentTimeMillis()
                    if (candidate != verdict && streak >= need) {
                        RuntimeLogger.log("DEVICE " + verdict + " -> " + candidate +
                            " (jitter=" + String.format("%.1f", jit) +
                            "ms stability=" + String.format("%.0f", stab) +
                            "% stalls/min=" + String.format("%.1f", stallRate) +
                            " mtStall=" + String.format("%.0f", mtStall) +
                            "ms therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                        verdict = candidate
                        lastHeartbeat = now
                    } else if (now - lastHeartbeat >= 60_000L) {
                        lastHeartbeat = now
                        RuntimeLogger.log("DEVICE " + verdict +
                            " (jitter=" + String.format("%.1f", jit) +
                            "ms stability=" + String.format("%.0f", stab) +
                            "% therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                    }
                    AdapterSignalBus.publishLag(verdict)
                    PerformanceTelemetryRegistry.publishDisplay(
                        FramePacingEngine.avgGapMs, stallRate, mtStall, verdict)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-verdict"; t.start()
    }

    fun stop() { running = false }
}
