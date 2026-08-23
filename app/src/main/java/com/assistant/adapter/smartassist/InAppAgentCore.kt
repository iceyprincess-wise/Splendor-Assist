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

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var lastObservation: RuntimeObservation? = null

    @Volatile
    private var lastDecision: AgentDecision? = null

    @Volatile
    private var lastVerification: ActionVerification? = null

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
     * True only when the agent flag is set AND a live scheduler exists.
     */
    fun isRunning(): Boolean = running.get() && scheduler != null

    fun stop() {
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

    private fun startInternal(): Boolean {
        if (!running.compareAndSet(false, true)) {
            return scheduler != null
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
        }
    }
}
