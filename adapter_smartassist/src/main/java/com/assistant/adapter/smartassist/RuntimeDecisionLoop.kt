package com.assistant.adapter.smartassist

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
 * This is what was missing: nothing drained contributions once the bus->
 * controller feedback loop was removed. Now the capture loop drives it.
 */
object RuntimeDecisionLoop {

    private val decisions = AtomicLong(0L)
    private val routed = AtomicLong(0L)
    private val idleNoContribution = AtomicLong(0L)
    private val idleUntrusted = AtomicLong(0L)

    @Volatile private var lastAction: String = "none"
    @Volatile private var lastWeight: Float = 0f
    @Volatile private var lastUpdatedMs: Long = 0L

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
        val best: EngineContribution? = contributions.maxByOrNull { it.weight }

        val emergency = ContributionRegistry.drainBest()

        val request = chooseRequest(frame, best, emergency)
        if (request == null) {
            idleNoContribution.incrementAndGet()
            lastAction = "idle-no-contribution"
            return false
        }

        val accepted = HybridExecutionTerminal.route(request)
        if (accepted) routed.incrementAndGet()
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
            startX = frame.ballX,
            startY = frame.ballY,
            endX = best.targetX,
            endY = best.targetY,
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
