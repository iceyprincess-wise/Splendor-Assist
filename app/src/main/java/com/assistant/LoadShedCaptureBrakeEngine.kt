package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























object LoadShedCaptureBrakeEngine {

    private const val POLL_MS = 500L

    @Volatile private var running = false
    @Volatile var executionBrake = 0; private set
    @Volatile private var lastLevel = "NONE"

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "lag-capture-brake"
        t.start()
        RuntimeLogger.log("LoadShedCaptureBrakeEngine started", "LOADSHED")
    }

    fun stop() { running = false }

    private fun poll() {
        val level = LoadShedGovernor.level
        val brake = when (level) {
            "HEAVY" -> 2
            "LIGHT" -> 1
            else    -> 0
        }
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

    /** Recommended capture interval Ms for current brake level. */
    fun recommendedIntervalMs(): Long = when (executionBrake) {
        2    -> 100L  // HEAVY: 10fps
        1    -> 66L   // LIGHT: 15fps
        else -> 0L    // NONE: use base rate (0 = no override)
    }

    /**
     * Returns true when HEAVY load shed is active and MOVE-class gesture
     * submissions should be suppressed (action-class gestures still pass).
     */
    fun suppressMoveGestures(): Boolean = executionBrake >= 2
}

// --- MERGED: LoadShedGovernor.kt ---

// V3 ADMIN-WIRED - quick to arm, slow to thrash, every knob live

/**
 * The rescue: when the judge says the device is struggling, this raises the
 * shed level (NONE / LIGHT / HEAVY) that the rest of the runtime obeys to
 * drop non-essential work. A SEIZURE stutter burst escalates IMMEDIATELY
 * (fast path, no waiting). V3: poll rhythm, arm/release confirm counts and
 * the minimum hold are all admin-tunable, re-read every poll.
 */
