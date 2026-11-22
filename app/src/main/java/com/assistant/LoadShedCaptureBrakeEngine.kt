package com.assistant

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

object LoadShedCaptureBrakeEngine {

    private const val POLL_MS = 500L

    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    @Volatile var executionBrake = 0; private set
    @Volatile private var lastLevel = "NONE"

    // U1 (item 26, g54a): FAST-PATH LATCH — the governor arm is 2 s poll
    // + 4-poll arm (8 s) + 8 s hold, so a hang is over before it arms.
    // LagVerdict CHOKING already published brake=2 to the bus, but this
    // engine's poll() re-derived the brake from the governor level ONLY
    // and re-published brake=0 within 500 ms — and OverlayService's
    // capture gate reads recommendedIntervalMs() from THIS engine's own
    // field, never the bus. So CHOKING (and BurstForensics SEIZURE)
    // reached SpeedCompensation but NEVER the capture gate: the fast
    // path existed on the bus and died here. Two independent latch
    // slots (lag verdict vs stutter forensics) so one source going
    // CALM/SMOOTH cannot clear the other's live brake. Wiring only —
    // no governor threshold or timing number is changed.
    @Volatile private var fastBrakeLag = 0
    @Volatile private var fastBrakeStutter = 0
    // L-F03 (item 26 lead, hunk H-C1 per Builder C spec TRACE-C §6): own
    // latch slots for the two new engines, so neither can clear the
    // other's live brake — same pattern as lag/stutter above.
    @Volatile private var fastBrakeFrameDrop = 0
    @Volatile private var fastBrakeFpsStab = 0

    /** LagVerdict CHOKING sets 2; any non-CHOKING verdict sets 0. */
    fun requestFastBrakeLag(level: Int) { fastBrakeLag = level.coerceIn(0, 2) }

    /** BurstForensics SEIZURE sets 2; CALM sets 0. */
    fun requestFastBrakeStutter(level: Int) { fastBrakeStutter = level.coerceIn(0, 2) }

    /** FrameDropPreventionEngine: measured gap burst sets 2, guard 1, calm 0. */
    fun requestFastBrakeFrameDrop(level: Int) { fastBrakeFrameDrop = level.coerceIn(0, 2) }

    /** FpsStabilisationEngine: sustained low measured rate sets 2/1, recovered 0. */
    fun requestFastBrakeFpsStab(level: Int) { fastBrakeFpsStab = level.coerceIn(0, 2) }

    /** Effective brake: governor level OR either fast-path latch, whichever sheds most. */
    internal fun computeBrake(governorLevel: String): Int {
        val govBrake = when (governorLevel) {
            "HEAVY" -> 2
            "LIGHT" -> 1
            else -> 0
        }
        return maxOf(govBrake, fastBrakeLag, fastBrakeStutter, fastBrakeFrameDrop, fastBrakeFpsStab)
    }

    internal fun pollOnce() { poll() }

    fun start() {
        if (running) return
        // Phase L (L-3): a previous poll thread still winding down must be
        // dead before a new one starts — otherwise two threads poll and
        // publish concurrently. Wait briefly; give up (no start) rather
        // than run two pollers. (Same pattern as InputLatencyEngine D18 /
        // Phase I I-2; the restart is real: AdapterRecoveryEngine
        // relaunches PerformanceEngineService in-process on a stale
        // heartbeat, and onCreate re-ignites these singleton objects.)
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        val t = Thread {
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "lag-capture-brake"
        pollThread = t
        t.start()
        RuntimeLogger.log("LoadShedCaptureBrakeEngine started", "LOADSHED")
    }

    fun stop() {
        running = false
        // Phase L (L-3): wake the sleeper so it exits now instead of up
        // to 500ms later (and cannot be resurrected by a quick restart).
        pollThread?.interrupt()
    }

    private fun poll() {
        val level = LoadShedGovernor.level
        val brake = computeBrake(level)
        val changed = brake != executionBrake || level != lastLevel
        executionBrake = brake
        lastLevel = level

        AdapterSignalBus.publishExecutionBrake(brake)
        // SPLENDOR_P7_DEVICE_BRAKE_SHED_BEGIN
        // Mirror into the cross-process device snapshot so main-process
        // FilterEngine reports the real brake.
        try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceBrake(brake) } catch (_: Throwable) {}
        // SPLENDOR_P7_DEVICE_BRAKE_SHED_END

        if (changed) {
            RuntimeLogger.log(
                "LoadShedBrake: loadShed=$level → executionBrake=$brake",
                "LOADSHED"
            )
        }
    }

    /** Recommended capture interval Ms for current brake level.
     * No68-C-D4: this used to have zero callers — the brake published to
     * the bus and stopped there, so LIGHT/HEAVY never throttled capture
     * despite this KDoc. OverlayService.captureFrameIntervalMs now reads
     * it (max with the memory-gate interval, floored at 30 fps in-game).
     */
    fun recommendedIntervalMs(): Long = when (executionBrake) {
        2    -> 100L  // HEAVY: 10fps
        1    -> 66L   // LIGHT: 15fps
        else -> 0L    // NONE: use base rate (0 = no override)
    }
}

// --- MERGED: LoadShedGovernor.kt ---

// V3 ADMIN-WIRED - quick to arm, slow to thrash, every knob live

/**
 * The rescue: when the judge says the device is struggling, this raises the
 * shed level (NONE / LIGHT / HEAVY) that the rest of the runtime obeys to
 * drop non-essential work.
 *
 * No68-C-D11 (false claims corrected): a SEIZURE burst does NOT escalate
 * immediately — it goes through the same 4-poll arm + 8 s hold as every
 * other level, and (No68-C-D35) it used to be swallowed by a 10 s boot
 * blackout as well; it now bypasses the blackout only, keeping the arm.
 * The knobs are NOT admin-tunable either: LoadShedGovernor's getters
 * return literals and no admin store is read. A re-tune changes the
 * literal in LoadShedGovernor.kt.
 */
