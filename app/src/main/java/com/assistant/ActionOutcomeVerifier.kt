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
    // L5 (batch 1): confirmed dispatches keep their pre-frame so verify()
    // can run the frame-effect compare (pre vs post). Bounded at 64 and
    // swept with the 800 ms window below; a missing pre-frame means the
    // policy verdict stands alone (see verify()).
    private val confirmedPreFrames = linkedMapOf<String, Pair<RuntimeFrame, ActionClass?>>()
    private val policy = com.assistant.execution.OutcomePolicy()
    private var dispatched = 0L; private var macroObserved = 0L; private var heuristicFailure = 0L
    private var effectConfirmedCount = 0L; private var effectFailedCount = 0L
    @Synchronized fun recordDispatch(request: ExecutionRequest, frame: RuntimeFrame) {
        if (pending.size >= 64) cancelDispatch(pending.keys.first())
        if (!pending.containsKey(request.actionId)) pending[request.actionId] = Pending(request, frame)
    }
    // D-27-STUTTER: the re-assert source for BurstForensicsEngine.
    // Returns the most recently recorded still-unconfirmed MOVE
    // request whose dispatch is at most maxAgeNanos old, or null.
    // MOVE-only: a MOVE stroke is a joystick hold — re-issuing it
    // extends the hold in the same direction. PASS / SHOT / CROSS /
    // KEEPER / DEFEND must never be re-fired (a second shot, pass or
    // save from one decision), and OutcomePolicy never cools or
    // judges MOVE, so a re-asserted MOVE cannot double-count an
    // outcome. The caller bounds maxAgeNanos by the bus STUTTER
    // budget (350ms), so the re-asserted direction is never older
    // than a request the bus itself would still accept.
    // A request recorded by RuntimeDecisionLoop before route() can be
    // returned in the microsecond window before a bus rejection
    // cancels it; its coordinates are still a genuine decision, and
    // the re-assert carries its own fresh actionId.
    @Synchronized fun lastFreshMove(maxAgeNanos: Long): ExecutionRequest? {
        val now = System.nanoTime()
        return pending.values.lastOrNull {
            it.request.actionClass == ActionClass.MOVE && now - it.at <= maxAgeNanos
        }?.request
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
        // L5 (batch 1): keep the pre-frame for the frame-effect compare.
        if (confirmedPreFrames.size >= 64) confirmedPreFrames.remove(confirmedPreFrames.keys.first())
        confirmedPreFrames[actionId] = item.frame to phase
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
        // L5 (batch 1): confirmed pre-frames are consumed by the loop
        // below; leftovers the policy never observes are evicted FIFO at
        // the 64-entry cap in confirmDispatch() (no age sweep — the map
        // stores no timestamps, and a stale pre-frame is only ever read
        // through the 800 ms policy window above).
        for ((id, observed) in policy.observe(currentFrame.ownerId, currentFrame.hasBall, currentFrame.trusted, now, currentFrame.capturedAtNanos)) {
            if (observed) macroObserved++ else heuristicFailure++
            // L5 (batch 1): the frame-effect verdict beside the policy
            // verdict. A dispatch counts as observed only when BOTH agree
            // it moved; without a pre-frame the policy verdict stands
            // alone (recorded as-is, never faked from a missing frame).
            val pre = confirmedPreFrames.remove(id)
            val effectConfirmed = if (pre != null) {
                FrameEffectVerifier.effectConfirmed(pre.first, currentFrame, pre.second)
            } else null
            // Without a pre-frame the policy verdict stands alone and the
            // effect counters are untouched (a policy-only verdict is not
            // a frame-effect verdict in either direction).
            val effect = effectConfirmed ?: observed
            if (effectConfirmed != null) {
                if (effectConfirmed) effectConfirmedCount++ else effectFailedCount++
            }
            GestureExecutionAuthority.recordEffectObserved(id, effect)
            RuntimeLogger.log(
                "FRAME_EFFECT actionId=$id effectConfirmed=$effect policyObserved=$observed " +
                    "preFrame=${if (pre != null) "present" else "missing"}",
                "FEEDBACK"
            )
            RuntimeLogger.log("${if (observed) "Macro_State_Correlation" else "Heuristic_Gesture_Failure"} actionId=$id", "FEEDBACK")
        }
    }
    @Synchronized fun reset() { pending.clear(); confirmedPreFrames.clear(); policy.reset(); dispatched = 0; macroObserved = 0; heuristicFailure = 0; effectConfirmedCount = 0; effectFailedCount = 0 }
    @Synchronized fun diagnostics(): Map<String, Any> = mapOf("dispatched" to dispatched,
        "macroObserved" to macroObserved, "heuristicFailure" to heuristicFailure,
        "effectConfirmed" to effectConfirmedCount, "effectFailed" to effectFailedCount,
        "pending" to pending.size, "evidenceKind" to "macro-state-correlation-not-internal-success")
}
