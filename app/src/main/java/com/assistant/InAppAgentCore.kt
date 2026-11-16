package com.assistant




























import com.assistant.diagnostic.RuntimeLogger









import com.assistant.runtime.*


















import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























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

    // SPLENDOR_P11_STARVATION: consecutive RunSelfHealCheck selections with
    // no verified recovery. At 5 the next cycle runs ReigniteFleet instead,
    // so a dead capture cannot starve the booster branch forever (4,328
    // self-heal cycles / 0 reignitions in the 2026-11-12 log).
    @Volatile
    private var consecutiveSelfHealCycles: Int = 0

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
            val decided = AgentDecisionPolicy.decide(before)

            // SPLENDOR_P11_STARVATION: RunSelfHealCheck cannot restore
            // boosterAlive (it never touches BoosterIgnition), so while the
            // capture is dead the agent selects it forever and ReigniteFleet
            // starves. After 5 consecutive selections with no verified
            // recovery, run ReigniteFleet on this cycle instead, then reset.
            // A verified recovery (ActionVerifier, honest since P11 D-06)
            // also resets the count. Single action per cycle is preserved.
            val decision = if (decided.action == AgentAction.RunSelfHealCheck) {
                if (lastVerification?.verified == true) consecutiveSelfHealCycles = 0
                consecutiveSelfHealCycles++
                if (consecutiveSelfHealCycles >= 5) {
                    consecutiveSelfHealCycles = 0
                    RuntimeLogger.log(
                        "AGENT STARVATION INTERLEAVE: RunSelfHealCheck selected 5 consecutive cycles with no verified recovery; running ReigniteFleet this cycle instead",
                        "AGENT"
                    )
                    decided.copy(
                        action = AgentAction.ReigniteFleet,
                        reason = decided.reason + " [P11 interleave: ReigniteFleet substituted after 5 consecutive self-heal cycles]"
                    )
                } else decided
            } else {
                consecutiveSelfHealCycles = 0
                decided
            }

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
                    "reason=${decision.reason} " +
                    "degraded=${before.health.degradedReasons}",
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
                    } else {
                        // SPLENDOR_P11_D05: was a silent skip — no log
                        // distinguished "reignition attempted" from "skipped".
                        RuntimeLogger.log(
                            "AGENT ACTION ReigniteFleet skipped: appContext()==null",
                            "AGENT"
                        )
                    }
                } else {
                    // SPLENDOR_P11_D05: was a silent skip — no log
                    // distinguished "reignition attempted" from "skipped".
                    RuntimeLogger.log(
                        "AGENT ACTION ReigniteFleet skipped: cooldown ${60_000L - (nowMs - lastReigniteMs)}ms remaining",
                        "AGENT"
                    )
                }
            }
        }
    }
}

/* ========
InputAccumulationDiagnosticsEngine
======== */
