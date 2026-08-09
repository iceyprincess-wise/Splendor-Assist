package com.assistant.adapter.smartassist

import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.atomic.AtomicLong
import com.assistant.diagnostic.registry.AdapterHealthRegistry

/*
 * RuntimeHealthMonitor answers one question:
 * "Is the runtime alive, stale, or degraded right now?"
 *
 * It does not own gameplay logic. It reads health surfaces already present
 * in the architecture and reports only REAL failures:
 *  - frame/decision freshness is measured by counter PROGRESS between
 *    evaluations (the old math compared 'now' against a timestamp taken
 *    inside the same call, so frame-stale could never fire and
 *    decision-stale fired for the wrong reason)
 *  - the decision loop runs once per assembled frame; when the frame flow
 *    itself is paused (menus, control rooms, game off screen) the loop is
 *    idle by design - the cause is reported as frame-stale, never blamed
 *    on the decision loop
 *  - a dispatcher that has never been asked to dispatch is idle, not stale
 *  - booster health reads the CROSS-PROCESS adapter heartbeats: the
 *    adapters live in their own processes, so only the persisted
 *    heartbeats are visible here, and only FRESH ones count
 */
object RuntimeHealthMonitor {

    private const val FRAME_STALE_MS = 3000L
    private const val DECISION_STALE_MS = 3000L
    private const val EXECUTION_STALE_MS = 5000L
    private const val BOOSTER_FRESH_MS = 120_000L

    private val evaluations = AtomicLong(0L)

    @Volatile
    private var lastEvaluatedMs: Long = 0L

    // frame-counter progress tracking between evaluations
    @Volatile private var prevFrames = -1L
    @Volatile private var framesProgressMs = 0L

    data class HealthState(
        val accessibilityAlive: Boolean,
        val overlayAlive: Boolean,
        val frameAlive: Boolean,
        val decisionAlive: Boolean,
        val busAlive: Boolean,
        val dispatchAlive: Boolean,
        val gameplayAlive: Boolean,
        val boosterAlive: Boolean,
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

        // ---- frame flow: alive while the frame counter keeps advancing ----
        val frames = (frame["frames"] as? Number)?.toLong() ?: 0L
        if (frames != prevFrames) {
            prevFrames = frames
            framesProgressMs = now
        }
        val frameFlowing = frames > 0L && now - framesProgressMs <= FRAME_STALE_MS
        val frameAlive = frame["state"] != "cold" && frameFlowing

        // ---- decision loop: judged against the frame flow it follows ----
        val decisions = (decision["decisions"] as? Number)?.toLong() ?: 0L
        val decisionLast = (decision["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val decisionFresh = decisionLast > 0L && now - decisionLast <= DECISION_STALE_MS
        val decisionAlive = when {
            decisions <= 0L -> false
            decisionFresh -> true
            // frame flow paused: the loop is idle by design, not stalled;
            // frame-stale carries the real cause
            !frameFlowing -> true
            // frames flowing but the loop is not keeping pace: genuine stall
            else -> false
        }

        val busAlive =
            (runtime["busEnabled"] as? Boolean ?: false) ||
                ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L

        // ---- dispatch: idle-with-nothing-requested is healthy ----
        val requested = (execution["requested"] as? Number)?.toLong() ?: 0L
        val acceptedCount = (execution["accepted"] as? Number)?.toLong() ?: 0L
        val executionLast = (execution["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val dispatchAlive = when {
            requested == 0L -> true
            acceptedCount > 0L -> true
            executionLast > 0L && now - executionLast <= EXECUTION_STALE_MS -> true
            else -> false
        }

        val gameplayAlive =
            ((registry["engines"] as? Number)?.toInt() ?: 0) > 0 &&
                (
                    decisions > 0L ||
                    ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L
                )

        // ---- booster: cross-process heartbeats, fresh ones only ----
        val boosterAlive =
            try {
                AdapterHealthRegistry.getAllLive().any {
                    now - it.lastHeartbeat <= BOOSTER_FRESH_MS
                }
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
            boosterAlive = boosterAlive,
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
            "boosterAlive" to state.boosterAlive,
            "degradedReasons" to state.degradedReasons.joinToString(","),
            "lastEvaluatedMs" to state.lastEvaluatedMs,
            "evaluations" to evaluations.get()
        )
    }

    fun reset() {
        evaluations.set(0L)
        lastEvaluatedMs = 0L
        prevFrames = -1L
        framesProgressMs = 0L
    }
}
