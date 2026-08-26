package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class InAppAgentSnapshot(
    val running: Boolean,
    val cycles: Long,
    val lastObservationMs: Long,
    val lastAction: String,
    val lastReason: String,
    val lastVerification: String,
    val lastVerified: Boolean
)

/**
 * Single in-process agent coordinator.
 *
 * Pipeline:
 *
 * RuntimeObservation
 *        ↓
 * AgentDecisionPolicy
 *        ↓
 * AgentAction
 *        ↓
 * existing runtime mechanism
 *        ↓
 * ActionVerifier
 *
 * This is not a second gameplay engine and does not replace
 * RuntimeCoordinator, RuntimeHealthMonitor or RuntimeSelfHealEngine.
 */
object InAppAgentCore {

    private const val INITIAL_DELAY_MS = 500L
    private const val CYCLE_DELAY_MS = 2_000L

    private val running = AtomicBoolean(false)
    private val cycles = AtomicLong(0L)

    // FIX #8: Lock to prevent race window during start transition
    private val startLock = Any()

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var lastObservation: RuntimeObservation? = null

    @Volatile
    private var lastDecision: AgentDecision? = null

    @Volatile
    private var lastVerification: ActionVerification? = null

    @Volatile
    private var lastReigniteMs: Long = 0L

    /**
     * Existing public API preserved.
     * It no longer throws bootstrap failures outward.
     */
    fun start() {
        startInternal()
    }

    /**
     * Explicit boolean bootstrap for App.onCreate() and future diagnostics.
     */
    fun tryStart(): Boolean = startInternal()

    /**
     * FIX #7: Stronger runtime truth test.
     * Proves the executor is actually alive, not just referenced.
     */
    fun isRunning(): Boolean {
        val exec = scheduler ?: return false
        return running.get() && !exec.isShutdown && !exec.isTerminated
    }

    fun stop() {
        // FIX #8: Synchronize stop to prevent concurrent modification
        synchronized(startLock) {
            if (!running.compareAndSet(true, false)) return

            scheduler?.shutdownNow()
            scheduler = null

            try {
                RuntimeLogger.log(
                    "InAppAgentCore stopped",
                    "AGENT"
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun runNow() {
        if (!isRunning()) {
            startInternal()
        }

        scheduler?.execute {
            tickSafely()
        }
    }

    fun snapshot(): InAppAgentSnapshot {
        val decision = lastDecision
        val verification = lastVerification

        return InAppAgentSnapshot(
            running = isRunning(),
            cycles = cycles.get(),
            lastObservationMs =
                lastObservation?.timestampMs ?: 0L,
            lastAction =
                decision?.action?.javaClass?.simpleName ?: "NONE",
            lastReason =
                decision?.reason ?: "No decision yet.",
            lastVerification =
                verification?.detail ?: "No verification yet.",
            lastVerified =
                verification?.verified ?: false
        )
    }

    /**
     * FIX #8: Synchronize the entire start transition to completely eliminate
     * the race window where running == true but scheduler == null.
     */
    private fun startInternal(): Boolean {
        synchronized(startLock) {
            if (running.get()) {
                val exec = scheduler
                return exec != null && !exec.isShutdown && !exec.isTerminated
            }

            var created: ScheduledExecutorService? = null

            return try {
                val executor =
                    Executors.newSingleThreadScheduledExecutor { task ->
                        Thread(
                            task,
                            "splendor-in-app-agent"
                        ).apply {
                            isDaemon = true
                            priority = Thread.NORM_PRIORITY
                        }
                    }

                created = executor
                scheduler = executor

                executor.scheduleWithFixedDelay(
                    { tickSafely() },
                    INITIAL_DELAY_MS,
                    CYCLE_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )

                // Set running to true ONLY after scheduler is fully assigned and scheduled
                running.set(true)

                try {
                    RuntimeLogger.log(
                        "InAppAgentCore started — " +
                            "observation/decision/action/verifier pipeline online",
                        "AGENT"
                    )
                } catch (_: Throwable) {
                }

                true
            } catch (t: Throwable) {
                running.set(false)

                try {
                    created?.shutdownNow()
                } catch (_: Throwable) {
                }

                scheduler = null

                try {
                    RuntimeLogger.log(
                        "InAppAgentCore failed to start: " +
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown"}",
                        "AGENT"
                    )
                } catch (_: Throwable) {
                }

                false
            }
        }
    }

    private fun tickSafely() {
        if (!running.get()) return

        try {
            val before = RuntimeObservation.capture()
            val decision = AgentDecisionPolicy.decide(before)

            lastObservation = before
            lastDecision = decision

            execute(decision.action)

            val after = RuntimeObservation.capture()
            val verification =
                ActionVerifier.verify(
                    decision.action,
                    before,
                    after
                )

            lastVerification = verification
            lastObservation = after

            val cycle = cycles.incrementAndGet()

            RuntimeLogger.log(
                "Agent cycle=$cycle " +
                    "action=${decision.action.javaClass.simpleName} " +
                    "priority=${decision.priority} " +
                    "verified=${verification.verified} " +
                    "reason=${decision.reason}",
                "AGENT"
            )

        } catch (t: Throwable) {

            RuntimeLogger.log(
                "InAppAgentCore cycle fault: " +
                    "${t.javaClass.simpleName}: ${t.message}",
                "AGENT"
            )
        }
    }

    private fun execute(action: AgentAction) {

        when (action) {

            AgentAction.ObserveOnly -> Unit

            AgentAction.RunSelfHealCheck -> {
                if (!RuntimeSelfHealEngine.isRunning()) {
                    RuntimeSelfHealEngine.start()
                } else {
                    RuntimeSelfHealEngine.runImmediateCheck()
                }
            }

            AgentAction.RefreshPerformance ->
                RuntimePerformanceCoordinator.refresh()

            // V6 PROMOTION: agent now FIXES booster-not-ready (60s cooldown)
            // instead of sitting in ObserveOnly while adapters stay silent.
            AgentAction.ReigniteFleet -> {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastReigniteMs >= 60_000L) {
                    lastReigniteMs = nowMs
                    val ctx = RuntimeSelfHealEngine.appContext()
                    if (ctx != null) {
                        try { com.assistant.BoosterIgnition.reset() } catch (_: Throwable) {}
                        try { com.assistant.BoosterIgnition.ensureIgnited(ctx) } catch (_: Throwable) {}
                        try { RuntimeCoordinator.refreshBoosterReadyFromRegistry() } catch (_: Throwable) {}
                        RuntimeLogger.log(
                            "AGENT ACTION ReigniteFleet: booster reset + re-ignited + G3 re-verified",
                            "AGENT"
                        )
                    }
                }
            }
        }
    }
}
