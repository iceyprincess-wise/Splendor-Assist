package com.assistant.adapter.lag

// V3 ADMIN-WIRED - every threshold live, publishes the Detector snapshot
import com.assistant.admin.AdminConfigStore
import com.assistant.admin.AdminLiveStats
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
    private val POLL_MS: Long get() = AdminConfigStore.getLong("lag.verdict.poll_ms", 2000L)
    private val JITTER_MS: Float get() = AdminConfigStore.get("lag.verdict.jitter_ms", 10f)
    private val STABILITY_PCT: Float get() = AdminConfigStore.get("lag.verdict.stability_pct", 65f)
    private val CHOKE_STALLS: Float get() = AdminConfigStore.get("lag.verdict.choke_stalls", 18f)
    private val CHOKE_MTSTALL_MS: Float get() = AdminConfigStore.get("lag.verdict.choke_mtstall_ms", 120f)
    private val CHOKE_SPIKES: Float get() = AdminConfigStore.get("lag.verdict.choke_spikes", 20f)
    private val CONFIRM_POLLS: Int get() = AdminConfigStore.getInt("lag.verdict.confirm_polls", 2)

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var candidate = "UNKNOWN"
    @Volatile private var streak = 0
    @Volatile private var lastHeartbeat = 0L

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
                    val raw = when {
                        stallRate > CHOKE_STALLS || mtStall > CHOKE_MTSTALL_MS ||
                            spm > CHOKE_SPIKES -> "CHOKING"
                        jit > JITTER_MS || stab < STABILITY_PCT -> "JITTERY"
                        FramePacingEngine.avgGapMs > 0f -> "SMOOTH"
                        else -> "UNKNOWN"
                    }
                    if (raw == candidate) streak++ else { candidate = raw; streak = 1 }
                    val need = if (CONFIRM_POLLS < 1) 1 else CONFIRM_POLLS
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
                    AdminLiveStats.publishLag(
                        FramePacingEngine.avgGapMs, jit, stab, stallRate,
                        mtStall, spm, verdict, ThermalPeekEngine.status,
                        DisplayProfileEngine.panelHz, LoadShedGovernor.level)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-verdict"; t.start()
    }

    fun stop() { running = false }
}
