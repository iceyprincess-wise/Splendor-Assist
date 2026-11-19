package com.assistant

import com.assistant.diagnostic.RuntimeLogger

/**
 * L8 — Optimizer Liveness Reporter (OptimizerLivenessReporter).
 *
 * Gap (Q-N7-4 / Q68-41): MemoryStabilityOptimizer may never have run —
 * its initialize() is gesture-gated (ActiveGestureController
 * .injectWinningVector, the only call site), logs nothing, and 0 gestures
 * were routed in the session; terminate() was never called — no teardown
 * hook existed.
 *
 * Contract: in = optimizer registration state; out = an
 * `optimizer=<registered|not>` line at startup + periodic, plus a
 * terminate() call on the controller's teardown path; caller =
 * ActiveGestureController (registration + shutdown) /
 * MemoryMonitorEngine (periodic report on its 5 s poll);
 * consumer = the heal log.
 *
 * Diagnostic producer: it only reports; it never starts, stops, or
 * retunes the optimizer. The startup line fires once (first
 * registration); the periodic line is throttled to 60 s inside the
 * reporter so the 5 s poller cannot spam the log. `state()` is the
 * single source of truth for the line: registered means the optimizer
 * instance exists AND initialize() has run; anything else reads `not`.
 */
object OptimizerLivenessReporter {

    private const val PERIODIC_THROTTLE_MS = 60_000L

    @Volatile private var optimizer: MemoryStabilityOptimizer? = null
    @Volatile private var startupReported = false
    @Volatile private var lastPeriodicMs: Long = 0L

    /** Call from ActiveGestureController init: records the instance and emits the startup line. */
    fun noteRegistered(optimizer: MemoryStabilityOptimizer) {
        this.optimizer = optimizer
        reportStartup()
    }

    fun state(): String {
        val current = optimizer
        return if (current != null && current.isInitialized()) "registered" else "not"
    }

    fun reportStartup() {
        if (startupReported) return
        startupReported = true
        try {
            RuntimeLogger.log("optimizer=${state()}", "MEMORY")
        } catch (_: Throwable) { }
    }

    /** Call from MemoryMonitorEngine's poll: throttled to one line per minute. */
    fun reportPeriodic(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastPeriodicMs < PERIODIC_THROTTLE_MS) return
        lastPeriodicMs = nowMs
        try {
            RuntimeLogger.log("optimizer=${state()}", "MEMORY")
        } catch (_: Throwable) { }
    }

    /** Call from ActiveGestureController.shutdown(): logs the teardown and drops the reference. */
    fun noteTerminated() {
        optimizer = null
        startupReported = false
        lastPeriodicMs = 0L
        try {
            RuntimeLogger.log("optimizer=terminated", "MEMORY")
        } catch (_: Throwable) { }
    }

    fun reset() {
        optimizer = null
        startupReported = false
        lastPeriodicMs = 0L
    }
}
