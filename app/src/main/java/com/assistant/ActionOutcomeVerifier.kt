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
        // SPLENDOR_P11_D09: a null actionClass used to drop the confirmation
        // silently — all 4 accepted INTERCEPTION gestures in the 2026-11-12
        // session were never verified. Fall back by source (INTERCEPTION
        // builders now also set DEFEND at the source); anything still
        // unclassifiable is logged, never silently dropped.
        val phase = item.request.actionClass
            ?: if (item.request.source == ExecutionSource.GOALKEEPER) ActionClass.KEEPER
            else if (item.request.source == ExecutionSource.INTERCEPTION) ActionClass.DEFEND
            else {
                RuntimeLogger.log("ActionOutcomeVerifier confirmDispatch dropped: no actionClass source=${item.request.source} phase=${item.request.phase} actionId=$actionId", "FEEDBACK")
                return
            }
        policy.dispatched(actionId, phase, item.frame.ownerId, item.frame.hasBall, System.nanoTime())
        dispatched++
    }
    @Synchronized fun cancelDispatch(actionId: String) { pending.remove(actionId); policy.cancel(actionId) }
    fun cooling(phase: ActionClass) = policy.cooling(phase, System.nanoTime())
    fun confidenceFactor(phase: ActionClass) = policy.confidenceFactor(phase)
    @Synchronized fun verify(currentFrame: RuntimeFrame) {
        // SPLENDOR_P11_D07: a blocked frame used to wipe both the pre-confirm
        // pending map and the policy's confirmed entries, destroying
        // legitimately-dispatched verifications inside their 800ms window.
        // Skip verification for this frame instead; entries stay for the next
        // unblocked verify, and the 800ms sweep below ages them out.
        if (com.assistant.execution.UiExecutionGate.blocked()) {
            return
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
