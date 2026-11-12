package com.assistant




























import com.assistant.diagnostic.RuntimeLogger





import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource


import com.assistant.runtime.*
import com.assistant.runtime.ActionClass




import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*



















import kotlinx.coroutines.cancel








object ActionOutcomeVerifier {
    private data class Pending(val request: ExecutionRequest, val frame: RuntimeFrame, val at: Long = System.nanoTime())
    private val pending = linkedMapOf<String, Pending>()
    private val policy = com.assistant.execution.OutcomePolicy()
    private var dispatched = 0L; private var macroObserved = 0L; private var heuristicFailure = 0L
    @Synchronized fun recordDispatch(request: ExecutionRequest, frame: RuntimeFrame) {
        if (pending.size >= 64) cancelDispatch(pending.keys.first())
        if (!pending.containsKey(request.actionId)) pending[request.actionId] = Pending(request, frame)
    }
    @Synchronized fun confirmDispatch(actionId: String) {
        val item = pending.remove(actionId) ?: return
        val phase = item.request.actionClass ?: if (item.request.source == ExecutionSource.GOALKEEPER) ActionClass.KEEPER else return
        policy.dispatched(actionId, phase, item.frame.ownerId, item.frame.hasBall, System.nanoTime())
        dispatched++
    }
    @Synchronized fun cancelDispatch(actionId: String) { pending.remove(actionId); policy.cancel(actionId) }
    fun cooling(phase: ActionClass) = policy.cooling(phase, System.nanoTime())
    fun confidenceFactor(phase: ActionClass) = policy.confidenceFactor(phase)
    @Synchronized fun verify(currentFrame: RuntimeFrame) {
        if (com.assistant.execution.UiExecutionGate.blocked()) {
            pending.clear(); policy.abandonPending(); return
        }
        val now = System.nanoTime()
        // 800ms matches the OutcomePolicy observe window: dropping at 500ms
        // discarded entries the policy could still judge, so confirmDispatch
        // after the drop never reached the policy (no cooling / no update).
        pending.entries.removeAll { now - it.value.at > 800_000_000L }
        for ((id, observed) in policy.observe(currentFrame.ownerId, currentFrame.hasBall, currentFrame.trusted, now, currentFrame.capturedAtNanos)) {
            if (observed) macroObserved++ else heuristicFailure++
            GestureExecutionAuthority.recordEffectObserved(id, observed)
            RuntimeLogger.log("${if (observed) "Macro_State_Correlation" else "Heuristic_Gesture_Failure"} actionId=$id", "FEEDBACK")
        }
    }
    @Synchronized fun reset() { pending.clear(); policy.reset(); dispatched = 0; macroObserved = 0; heuristicFailure = 0 }
    @Synchronized fun diagnostics(): Map<String, Any> = mapOf("dispatched" to dispatched,
        "macroObserved" to macroObserved, "heuristicFailure" to heuristicFailure,
        "pending" to pending.size, "evidenceKind" to "macro-state-correlation-not-internal-success")
}
