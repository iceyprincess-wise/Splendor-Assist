package com.assistant.adapter.lag

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * LoadShedCaptureBrakeEngine — load shed → execution feedback bridge.
 *
 * PROVEN GAP: LoadShedGovernor.level is published to
 * PerformanceTelemetryRegistry and stops there. No component with
 * execution authority (OverlayService capture loop, gesture queue)
 * reads the load shed level to adjust behavior.
 *
 * This engine translates load shed level into an execution throttle
 * signal published to AdapterSignalBus, consumed by OverlayService's
 * capture gate and the gesture submission path.
 *
 *   NONE  → executionBrake = 0  (normal operation)
 *   LIGHT → executionBrake = 1  (reduce capture rate 15fps, keep gestures)
 *   HEAVY → executionBrake = 2  (10fps capture, suppress MOVE-class only gestures)
 *
 * The brake is polled every 500ms (faster than LoadShedGovernor's 2s poll)
 * so it responds within one poll cycle after the governor decides.
 */
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
        val brake = if (AdapterSignalBus.manualPerformanceEscalation) {
            2
        } else {
            when (level) {
                "HEAVY" -> 2
                "LIGHT" -> 1
                else    -> 0
            }
        }
        val changed = brake != executionBrake || level != lastLevel
        executionBrake = brake
        lastLevel = level

        AdapterSignalBus.publishExecutionBrake(brake)

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
