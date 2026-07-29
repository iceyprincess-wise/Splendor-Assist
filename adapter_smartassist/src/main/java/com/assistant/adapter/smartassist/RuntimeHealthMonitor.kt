package com.assistant.adapter.smartassist

import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.atomic.AtomicLong
import com.assistant.diagnostic.registry.AdapterHealthRegistry

/*
 * RuntimeHealthMonitor answers one question:
 * "Is the runtime alive, stale, or degraded right now?"
 *
 * It does not own gameplay logic.
 * It reads health surfaces already present in the architecture:
 *   RuntimeCoordinator
 *   FrameAssembler
 *   RuntimeDecisionLoop
 *   ContributionRegistry
 *   GestureExecutionAuthority
 *   GameplayEngineRegistry
 */
object RuntimeHealthMonitor {

    private const val FRAME_STALE_MS = 3000L
    private const val DECISION_STALE_MS = 3000L
    private const val EXECUTION_STALE_MS = 5000L

    private val evaluations = AtomicLong(0L)

    @Volatile
    private var lastEvaluatedMs: Long = 0L

    data class HealthState(
        val accessibilityAlive: Boolean,
        val overlayAlive: Boolean,
        val frameAlive: Boolean,
        val decisionAlive: Boolean,
        val busAlive: Boolean,
        val dispatchAlive: Boolean,
        val gameplayAlive: Boolean,
        val degradedReasons: List<String>,
        val lastEvaluatedMs: Long
    )

    fun snapshot(): HealthState {
        evaluations.incrementAndGet()
        val now = System.currentTimeMillis()
        lastEvaluatedMs = now

        val runtime = RuntimeCoordinator.runtimeState()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val contributions =
            com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
        val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
        val registry = GameplayEngineRegistry.registryRuntimeSnapshot()

        val accessibilityAlive =
            runtime["accessibilityReady"] as? Boolean ?: false

        val overlayAlive =
            runtime["captureReady"] as? Boolean ?: false

        val frameAlive =
            frame["state"] != "cold" &&
                frameFresh(frame, now)

        val decisionAlive =
            decisionFresh(decision, now)

        val busAlive =
            (runtime["busEnabled"] as? Boolean ?: false) ||
                ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L

        val dispatchAlive =
            executionFresh(execution, now)

        val gameplayAlive =
            ((registry["engines"] as? Number)?.toInt() ?: 0) > 0 &&
                (
                    ((decision["decisions"] as? Number)?.toLong() ?: 0L) > 0L ||
                    ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L
                )

        val boosterAlive =
            try {
                AdapterHealthRegistry.getAll().isNotEmpty()
            } catch (_: Throwable) {
                false
            }

        val degraded = mutableListOf<String>()

        if (!accessibilityAlive) degraded += "accessibility-not-ready"
        if (!overlayAlive) degraded += "capture-not-ready"
        if (!frameAlive) degraded += "frame-stale"
        if (!decisionAlive) degraded += "decision-stale"
        if (!busAlive) degraded += "bus-idle"
        if (!dispatchAlive) degraded += "dispatch-stale"
        if (!gameplayAlive) degraded += "gameplay-idle"
        if (!boosterAlive) degraded += "booster-not-ready"

        return HealthState(
            accessibilityAlive = accessibilityAlive,
            overlayAlive = overlayAlive,
            frameAlive = frameAlive,
            decisionAlive = decisionAlive,
            busAlive = busAlive,
            dispatchAlive = dispatchAlive,
            gameplayAlive = gameplayAlive,
            degradedReasons = degraded,
            lastEvaluatedMs = lastEvaluatedMs
        )
    }

    fun runtimeHealthSnapshot(): Map<String, Any> {
        val state = snapshot()
        return mapOf(
            "accessibilityAlive" to state.accessibilityAlive,
            "overlayAlive" to state.overlayAlive,
            "frameAlive" to state.frameAlive,
            "decisionAlive" to state.decisionAlive,
            "busAlive" to state.busAlive,
            "dispatchAlive" to state.dispatchAlive,
            "gameplayAlive" to state.gameplayAlive,
            "boosterAlive" to try { AdapterHealthRegistry.getAll().isNotEmpty() } catch (_: Throwable) { false },
            "degradedReasons" to state.degradedReasons.joinToString(","),
            "lastEvaluatedMs" to state.lastEvaluatedMs,
            "evaluations" to evaluations.get()
        )
    }

    private fun frameFresh(
        snapshot: Map<String, Any>,
        now: Long
    ): Boolean {
        val frames =
            (snapshot["frames"] as? Number)?.toLong() ?: 0L
        return frames > 0L && now - lastEvaluatedMs <= FRAME_STALE_MS
    }

    private fun decisionFresh(
        snapshot: Map<String, Any>,
        now: Long
    ): Boolean {
        val last =
            (snapshot["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        return last > 0L && now - last <= DECISION_STALE_MS
    }

    private fun executionFresh(
        snapshot: Map<String, Any>,
        now: Long
    ): Boolean {
        val last =
            (snapshot["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val accepted =
            (snapshot["accepted"] as? Number)?.toLong() ?: 0L
        return (last > 0L && now - last <= EXECUTION_STALE_MS) || accepted > 0L
    }

    fun reset() {
        evaluations.set(0L)
        lastEvaluatedMs = 0L
    }
}
