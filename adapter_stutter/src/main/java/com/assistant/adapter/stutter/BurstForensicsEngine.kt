package com.assistant.adapter.stutter

// V3 ADMIN-WIRED - every classification line answers the admin store live
import com.assistant.admin.AdminConfigStore
import com.assistant.admin.AdminLiveStats
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * Classifies each burst and keeps a 60s picture:
 *   HICCUP      isolated burst, recovers next slice
 *   OSCILLATION several bursts inside the watch window - rhythmic micro-stutter
 *   SEIZURE     burst with a frame past the freeze line - a felt freeze
 * Publishes one state for the lag governor's FAST path (a SEIZURE here
 * escalates the rescue to HEAVY immediately). Aggregated logging only.
 *
 * V3: the freeze line, oscillation count + window, calm-restore time and
 * calm-check rhythm are all admin-tunable, re-read on every burst - no
 * restart needed. State changes publish instantly for the admin Detector.
 */
object BurstForensicsEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val SEIZURE_MS: Float get() = AdminConfigStore.get("stutter.forensics.seizure_ms", 150f)
    private val OSC_BURSTS: Int get() = AdminConfigStore.getInt("stutter.forensics.osc_bursts", 3)
    private val OSC_WINDOW_MS: Long get() = AdminConfigStore.getLong("stutter.forensics.osc_window_ms", 15000L)
    private val CALM_AFTER_MS: Long get() = AdminConfigStore.getLong("stutter.forensics.calm_after_ms", 10000L)
    private val DECAY_POLL_MS: Long get() = AdminConfigStore.getLong("stutter.forensics.decay_poll_ms", 5000L)

    @Volatile var state = "CALM"; private set
    @Volatile private var decayRunning = false
    @Volatile private var lastLogMs = 0L
    private val recent = ArrayDeque<Long>()   // burst timestamps, last 60s

    fun bursts60s(): Int = synchronized(this) { recent.size }

    @Synchronized
    fun record(frames: Int, worstMs: Float) {
        val now = System.currentTimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 60_000L) recent.removeFirst()
        val window = if (OSC_WINDOW_MS > 0) OSC_WINDOW_MS else 1L
        val inWindow = recent.count { now - it <= window }
        val needOsc = if (OSC_BURSTS < 1) 1 else OSC_BURSTS
        val next = when {
            worstMs >= SEIZURE_MS -> "SEIZURE"
            inWindow >= needOsc -> "OSCILLATION"
            else -> "HICCUP"
        }
        val changed = next != state
        state = next
        PerformanceTelemetryRegistry.publishStutter(state, recent.size.toFloat(), worstMs)
        // CRITICAL FIX PHASE3: publishStutter was NEVER called — stutterIsSevere was always false.
        // SpeedCompensationContributor, LoadShedGovernor all read this — all were blind to stutter.
        AdapterSignalBus.publishStutter(state)
        if (changed) {
            try {
                AdminLiveStats.publishStutter(
                    StutterPulseEngine.burstsPerMin, worstMs, frames,
                    state, StutterPulseEngine.panelHz)
            } catch (_: Throwable) { }
        }
        if (changed || now - lastLogMs >= 30_000L) {
            lastLogMs = now
            RuntimeLogger.log("burst " + state + " frames=" + frames +
                " worst=" + String.format("%.0f", worstMs) +
                "ms bursts60s=" + recent.size + " inWindow=" + inWindow, "STUTTER")
        }
    }

    /** decay back to CALM after an admin-tunable quiet time */
    fun startDecay() {
        if (decayRunning) return
        decayRunning = true
        val t = Thread {
            while (decayRunning) {
                try {
                    val nap = DECAY_POLL_MS
                    Thread.sleep(if (nap > 0) nap else 1L)
                    val now = System.currentTimeMillis()
                    val quiet = if (CALM_AFTER_MS > 0) CALM_AFTER_MS else 1L
                    synchronized(this) {
                        if (state != "CALM" &&
                            (recent.isEmpty() || now - recent.last() > quiet)) {
                            state = "CALM"
                            AdapterSignalBus.publishStutter("CALM")  // PHASE3 fix
                            PerformanceTelemetryRegistry.publishStutter("CALM", 0f, 0f)
                            try {
                                AdminLiveStats.publishStutter(
                                    StutterPulseEngine.burstsPerMin, 0f, 0,
                                    "CALM", StutterPulseEngine.panelHz)
                            } catch (_: Throwable) { }
                            RuntimeLogger.log("burst CALM restored", "STUTTER")
                        }
                    }
                } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-decay"; t.start()
    }

    fun stopDecay() { decayRunning = false }
}
