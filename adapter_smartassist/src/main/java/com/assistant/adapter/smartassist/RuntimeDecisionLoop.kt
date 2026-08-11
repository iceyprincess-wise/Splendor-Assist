package com.assistant.adapter.smartassist

import com.assistant.admin.AdminConfigStore
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong

/*
 * The single per-frame decision path.
 *
 *   frame -> registry.collect -> pick highest-weight contribution
 *         -> also fold in ContributionRegistry.drainBest (emergency submitters)
 *         -> ONE ExecutionRequest routed through the terminal
 *
 * REPAIRED (Task C - FIELD-LOG PROVEN): arbitration was raw max-weight.
 * The 18:38 session shows what that produced: MagneticFeet (a MOVE-class
 * stabilizer that fires with authority~1.0 on EVERY possession frame) won
 * nearly every cycle - lastAction=MagneticFeet:MOVE, weight=1.0 - so 6131
 * accepted dispatches were overwhelmingly ball-position taps while Shot
 * won 1767, PassLane 907, CrossDelivery 23. Actions existed; they were
 * being outvoted by a stabilizer.
 *
 * Fix: ACTION-CLASS ARBITRATION SCALING. Real match actions (SHOT, PASS,
 * CROSS, DEFEND, KEEPER, EVADE) keep full weight; MOVE support is scaled
 * down so it wins only when nothing real is on offer. Admin-tunable live:
 *   assist.decision.move_scale (default 0.45)
 * Raise it if movement support feels too weak, lower it if MOVE spam
 * returns - no rebuild needed.
 */
object RuntimeDecisionLoop {

    private val decisions = AtomicLong(0L)
    private val routed = AtomicLong(0L)
    private val idleNoContribution = AtomicLong(0L)
    private val idleUntrusted = AtomicLong(0L)

    @Volatile private var lastAction: String = "none"
    @Volatile private var lastWeight: Float = 0f
    @Volatile private var lastUpdatedMs: Long = 0L

    private fun classScale(actionClass: ActionClass): Float =
        when (actionClass) {
            ActionClass.MOVE ->
                try { AdminConfigStore.get("assist.decision.move_scale", 0.45f) }
                catch (_: Throwable) { 0.45f }
            ActionClass.NONE -> 0f
            else -> 1f
        }

    /* Called once per assembled frame by OverlayService's capture loop. */
    fun onFrame(frame: RuntimeFrame): Boolean {
        decisions.incrementAndGet()
        lastUpdatedMs = System.currentTimeMillis()

        if (!frame.trusted) {
            idleUntrusted.incrementAndGet()
            lastAction = "idle-untrusted"
            return false
        }

        val contributions = GameplayEngineRegistry.collect(frame)
        val best: EngineContribution? =
            contributions.maxByOrNull { it.weight * classScale(it.actionClass) }

        val emergency = ContributionRegistry.drainBest()

        val request = chooseRequest(frame, best, emergency)
        if (request == null) {
            idleNoContribution.incrementAndGet()
            lastAction = "idle-no-contribution"
            return false
        }

        val accepted = HybridExecutionTerminal.route(request)
        if (accepted) {
            routed.incrementAndGet()
            try {
                com.assistant.events.GameplayEventHub.emit(
                    "routed",
                    "source=${request.source} phase=${request.phase}"
                )
            } catch (_: Throwable) {
            }
        }
        lastAction = describe(best, emergency)
        lastWeight = best?.weight ?: 0f
        return accepted
    }

    private fun chooseRequest(
        frame: RuntimeFrame,
        best: EngineContribution?,
        emergency: ExecutionRequest?
    ): ExecutionRequest? {
        // Emergency (goalkeeper etc.) outranks a normal contribution.
        if (emergency != null) {
            if (best == null) return emergency
            val emergencyPriority = HybridExecutionTerminal.priority(emergency.source)
            val normalPriority = HybridExecutionTerminal.priority(ExecutionSource.SMART_ASSIST)
            if (emergencyPriority > normalPriority) return emergency
        }
        if (best == null) return null

        return ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = best.actionClass.ordinal,
            startX = 250f,  // FIX: joystick origin, not ball position
            startY = 550f,
            endX = best.targetX.coerceAtLeast(0f),
            endY = best.targetY.coerceAtLeast(0f),
            duration = best.durationHintMs.coerceIn(15L, 85L)
        )
    }

    private fun describe(best: EngineContribution?, emergency: ExecutionRequest?): String =
        when {
            emergency != null && best == null -> "emergency:${emergency.source}"
            best != null -> "${best.engine}:${best.actionClass}"
            else -> "none"
        }

    fun reset() {
        decisions.set(0L); routed.set(0L)
        idleNoContribution.set(0L); idleUntrusted.set(0L)
        lastAction = "none"; lastWeight = 0f; lastUpdatedMs = 0L
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "decisions" to decisions.get(),
        "routed" to routed.get(),
        "idleUntrusted" to idleUntrusted.get(),
        "idleNoContribution" to idleNoContribution.get(),
        "lastAction" to lastAction,
        "lastWeight" to lastWeight,
        "lastUpdatedMs" to lastUpdatedMs
    )
}
