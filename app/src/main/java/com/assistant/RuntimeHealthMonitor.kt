package com.assistant

































import com.assistant.execution.ContributionRegistry




import com.assistant.runtime.*




import com.assistant.runtime.GameplayEngineRegistry



















import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object RuntimeHealthMonitor {

    private const val FRAME_STALE_MS = 3000L
    private const val DECISION_STALE_MS = 3000L
    private const val EXECUTION_STALE_MS = 5000L
    // Q68-44 (batch 5b): bus-offer freshness window — same 3 s as decision
    // freshness, because an offer is a decision product.
    private const val BUS_STALE_MS = 3000L

    private val evaluations = AtomicLong(0L)

    @Volatile
    private var lastEvaluatedMs: Long = 0L

    // frame-counter progress tracking between evaluations
    @Volatile private var prevFrames = -1L
    @Volatile private var framesProgressMs = 0L

    // Q68-44 (batch 5b): bus-offer latch — the last time the offered
    // counter ROSE, and the highest offered value seen. snapshot() is
    // @Synchronized, so these are only ever touched under that lock.
    @Volatile private var offeredLastMs = 0L
    @Volatile private var lastOfferedSeen = 0L

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

    // No68-E1-D36: snapshot() reads and writes prevFrames/framesProgressMs
    // as a pair, but it was unsynchronised while MainActivity (3s hub
    // tick), the control rooms and RuntimeObservation.capture() call it
    // from different threads — two concurrent snapshots could interleave
    // the check and the update and report frame flow that never happened
    // (or miss flow that did). The whole evaluation is now atomic.
    @Synchronized
    fun snapshot(): HealthState {
        evaluations.incrementAndGet()
        val now = System.currentTimeMillis()
        lastEvaluatedMs = now

        val runtime = RuntimeCoordinator.runtimeState()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val contributions =
            com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
        // D6: a rejected emergency submission (a save that never reached
        // the bus) is surfaced here as a degraded reason, so the
        // "impossible to miss" claim in ContributionRegistry is true in a
        // decision path, not only in the MainActivity raw dump.
        val emergencyRejected =
            (contributions["emergencyRejected"] as? Number)?.toLong() ?: 0L
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

        // Q68-44 (batch 5b): busAlive was cumulative — offered > 0 forever,
        // so one offer in the first minute kept the bus "alive" for the
        // whole session even after the bus died (same shape D37/D38 fixed
        // for dispatchAlive/gameplayAlive). Latch the last offer: alive
        // means the bus is enabled, or an offer arrived within BUS_STALE_MS.
        // The latch tracks the counter rising, not the clock alone, so a
        // reset (ContributionRegistry.clear() zeroes offered) cannot fake
        // freshness — offered must RISE again to re-arm.
        val offered = (contributions["offered"] as? Number)?.toLong() ?: 0L
        if (offered > lastOfferedSeen) {
            lastOfferedSeen = offered
            offeredLastMs = now
        }
        val busAlive =
            (runtime["busEnabled"] as? Boolean ?: false) ||
                (offered > 0L && now - offeredLastMs <= BUS_STALE_MS)

        // ---- dispatch: idle-with-nothing-requested is healthy ----
        val requested = (execution["requested"] as? Number)?.toLong() ?: 0L
        val acceptedCount = (execution["accepted"] as? Number)?.toLong() ?: 0L
        val executionLast = (execution["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        // No68-E1-D37: acceptedCount > 0 used to report dispatch alive
        // FOREVER — accepted is cumulative, so one accepted gesture in
        // the first minute kept dispatchAlive true for the whole session
        // even after the dispatcher died. Alive now means: nothing was
        // ever requested, or everything requested was accepted, or the
        // execution record is fresh (a dispatch is in flight).
        val dispatchAlive = when {
            requested == 0L -> true
            acceptedCount >= requested -> true
            executionLast > 0L && now - executionLast <= EXECUTION_STALE_MS -> true
            else -> false
        }

        // No68-E1-D38: gameplayAlive had the same cumulative-count shape
        // (decisions > 0 OR offered > 0, no freshness) — one decision in
        // the first minute kept gameplay alive forever after the loop
        // died. Anchor it on decision freshness, with the same idle
        // allowance the decision verdict already uses: when frame flow
        // is paused the loop is idle by design, not dead — but only if
        // it has ever produced anything; a loop that never decided
        // anything is still idle, paused or not.
        val everProduced = decisions > 0L ||
            ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L
        val gameplayAlive =
            ((registry["engines"] as? Number)?.toInt() ?: 0) > 0 &&
                (
                    decisionFresh ||
                    (!frameFlowing && everProduced)
                )

        // ---- booster: cross-process heartbeats, fresh ones only ----
        val boosterAlive = try { com.assistant.BoosterIgnition.isFleetReady() } catch (_: Throwable) { false }

        val degraded = mutableListOf<String>()

        if (!accessibilityAlive) degraded += "accessibility-not-ready"
        if (!overlayAlive) degraded += "capture-not-ready"
        if (!frameAlive) degraded += "frame-stale"
        if (!decisionAlive) degraded += "decision-stale"
        if (!busAlive) degraded += "bus-idle"
        if (!dispatchAlive) degraded += "dispatch-stale"
        if (!gameplayAlive) degraded += "gameplay-idle"
        if (!boosterAlive) degraded += "booster-not-ready"
        if (emergencyRejected > 0L) degraded += "emergency-rejected"

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

    @Synchronized
    fun reset() {
        evaluations.set(0L)
        lastEvaluatedMs = 0L
        prevFrames = -1L
        framesProgressMs = 0L
        // Q68-44 (batch 5b): the bus-offer latch is epoch state too — a
        // reset without it would keep a stale offeredLastMs and report
        // the bus alive into the next epoch on zero new offers.
        offeredLastMs = 0L
        lastOfferedSeen = 0L
    }
}

/* ========
RuntimePerformanceCoordinator
======== */
