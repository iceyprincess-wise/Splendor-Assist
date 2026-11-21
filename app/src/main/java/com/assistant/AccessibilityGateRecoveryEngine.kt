package com.assistant

/**
 * B3 — Accessibility Gate Recovery (engine-ideas §2, N2-trace §3.2.1).
 *
 * Bottleneck: the bus never restarted while the G1 accessibility gate
 * was lost — the consumer thread was gone, so the bus stayed stopped
 * for 26 minutes.
 *
 * WHAT THIS IS NOT: a bus restart. Restarting the bus is FORBIDDEN
 * (unsafe) — this engine never calls CentralExecutionBus.stop/start.
 * It re-establishes the gate's CONSUMER: the SmartAssist bus thread +
 * busRunnable inside the accessibility service, which is what actually
 * drains the bus. When the service reconnects, RuntimeCoordinator's
 * normal G5 path (evaluate → bus start → startExecutionLoop) re-enables
 * the bus on its own; this engine only repairs the consumer so that
 * path has something to run.
 *
 * TRACE: CALLER RuntimeSelfHealEngine.runChecks (5 s cycle) → ENGINE
 * this recovery (decide + recover) → CALLEE
 * SmartAssistAccessibilityEngine.restartBusConsumer → DATA FLOW G1
 * state + consumer liveness → DECISION recover the consumer when G1
 * has been lost >10 s while capture is alive and the service instance
 * is alive but its consumer is dead; wait when no service instance
 * exists (only the system can rebind the service — the app cannot
 * force it, and the wait is logged, not silent) → MUTATION new bus
 * thread + re-posted busRunnable + G1 re-reported → CONSUMER
 * CentralExecutionBus (its submissions are drained again) → LIVE
 * EFFECT the bus accepts submissions again after a G1 loss instead of
 * staying stopped until the next cold start.
 *
 * The 10 s grace exists so a transient interrupt (a 1–2 s service
 * hiccup) does not trigger a thread rebuild mid-match.
 */
object AccessibilityGateRecoveryEngine {

    /** G1 must be lost at least this long before recovery is attempted. */
    const val GATE_LOST_GRACE_MS = 10_000L

    /** At most one GATE_RECOVERY_WAITING line per interval. */
    const val WAITING_LOG_INTERVAL_MS = 60_000L

    enum class RecoveryDecision { NO_ACTION, RECOVER_CONSUMER, WAIT_FOR_SERVICE }

    @Volatile private var gateLostSinceMs = 0L
    @Volatile private var lastWaitingLogMs = 0L
    @Volatile private var lastRecoveredLogMs = 0L

    /**
     * Pure decision core (harness-pinned). `gateLostMs` is how long G1
     * has been continuously lost; `consumerAlive` is the service's bus
     * consumer thread, not the bus itself.
     */
    fun decide(
        gateLostMs: Long,
        captureAlive: Boolean,
        serviceAlive: Boolean,
        consumerAlive: Boolean
    ): RecoveryDecision = when {
        gateLostMs < GATE_LOST_GRACE_MS -> RecoveryDecision.NO_ACTION
        !captureAlive -> RecoveryDecision.NO_ACTION
        consumerAlive -> RecoveryDecision.NO_ACTION
        !serviceAlive -> RecoveryDecision.WAIT_FOR_SERVICE
        else -> RecoveryDecision.RECOVER_CONSUMER
    }

    /**
     * Periodic check, called from the self-heal 5 s cycle. Returns the
     * decision taken (for the heal log / tests).
     */
    fun checkAndRecover(nowMs: Long = System.currentTimeMillis()): RecoveryDecision {
        val g1Ready = try { RuntimeCoordinator.isAccessibilityReady() } catch (_: Throwable) { true }
        if (g1Ready) {
            gateLostSinceMs = 0L
            return RecoveryDecision.NO_ACTION
        }
        if (gateLostSinceMs == 0L) gateLostSinceMs = nowMs
        val gateLostMs = nowMs - gateLostSinceMs
        val captureAlive = try { RuntimeCoordinator.isCaptureReady() } catch (_: Throwable) { false }
        val service = SmartAssistAccessibilityEngine.globalInstance
        val decision = decide(
            gateLostMs = gateLostMs,
            captureAlive = captureAlive,
            serviceAlive = service != null,
            consumerAlive = service?.isBusConsumerAlive() ?: false
        )
        when (decision) {
            RecoveryDecision.NO_ACTION -> Unit
            RecoveryDecision.WAIT_FOR_SERVICE -> {
                if (nowMs - lastWaitingLogMs >= WAITING_LOG_INTERVAL_MS) {
                    lastWaitingLogMs = nowMs
                    try {
                        com.assistant.diagnostic.RuntimeLogger.log(
                            "GATE_RECOVERY_WAITING gateLostMs=$gateLostMs captureAlive=$captureAlive (no accessibility service instance: only the system can rebind it; bus stays paused, no restart attempted)",
                            "RUNTIME"
                        )
                    } catch (_: Throwable) { }
                }
            }
            RecoveryDecision.RECOVER_CONSUMER -> {
                val ok = try { service!!.restartBusConsumer() } catch (_: Throwable) { false }
                if (ok) {
                    gateLostSinceMs = 0L
                    if (nowMs - lastRecoveredLogMs >= WAITING_LOG_INTERVAL_MS) {
                        lastRecoveredLogMs = nowMs
                        try {
                            com.assistant.diagnostic.RuntimeLogger.log(
                                "GATE_RECOVERED gateLostMs=$gateLostMs (bus consumer thread re-established; G1 re-reported, coordinator G5 path re-enables the bus)",
                                "RUNTIME"
                            )
                        } catch (_: Throwable) { }
                    }
                } else {
                    try {
                        com.assistant.diagnostic.RuntimeLogger.log(
                            "GATE_RECOVERY_FAILED gateLostMs=$gateLostMs (consumer restart refused; will retry next cycle)",
                            "RUNTIME"
                        )
                    } catch (_: Throwable) { }
                }
            }
        }
        return decision
    }
}
