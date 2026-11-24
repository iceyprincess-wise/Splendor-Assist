package com.assistant

import com.assistant.runtime.ActionClass
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * L5 — Frame-Effect Verifier (FrameEffectVerifier).
 *
 * Gap (Q63-2 PARTIAL / Q-N5-3): dispatches were fire-and-forget — the
 * outcome verifier reported 0 confirmed successful gestures and one
 * accepted dispatch was never observed executing.
 *
 * Contract: in = dispatched actionId + pre/post frames; out =
 * `effectConfirmed=<bool>` per actionId; caller = ActionOutcomeVerifier
 * (its verify() pass, beside the OutcomePolicy macro-state verdict);
 * consumer = GestureExecutionAuthority.recordEffectObserved (the effect
 * record the loop's cooling reads) + the log.
 *
 * This is a small-region frame compare in the literal sense: it compares
 * the ball's pre/post displacement (and ownership/hasBall flips) between
 * the frame the dispatch was recorded on and the frame the verifier is
 * judging. Thresholds are per action class, because a MOVE only has to
 * move the ball while a PASS has to change who has it:
 *   PASS/SHOT/CROSS: owner changed, hasBall flipped, or ball moved > 8px
 *   MOVE/EVADE:      ball moved > 2px (the carrier moved)
 *   KEEPER/DEFEND:   ball moved > 4px or hasBall flipped (the threat moved)
 *   NONE/null:       ball moved > 2px or owner changed (no class to judge)
 * A non-finite ball coordinate on either side reads as no effect, never
 * as a confirmed one. The policy verdict is NOT replaced: the verifier
 * reports `effectConfirmed` AND the policy verdict side by side, and the
 * effect record takes the conjunction — a dispatch counts as observed
 * only when both the macro state and the frame delta agree it moved.
 * When they disagree the FRAME_EFFECT line names the split, so the next
 * log-proof round can tell a policy miss from a frame miss.
 *
 * Pure Kotlin (no Android): pinned in the batch-1 harness (group 20).
 */
object FrameEffectVerifier {

    fun effectConfirmed(pre: RuntimeFrame, post: RuntimeFrame, actionClass: ActionClass?): Boolean {
        if (!pre.ballX.isFinite() || !pre.ballY.isFinite() ||
            !post.ballX.isFinite() || !post.ballY.isFinite()
        ) return false
        val moved = hypot(
            (post.ballX - pre.ballX).toDouble(),
            (post.ballY - pre.ballY).toDouble()
        ).toFloat()
        val ownerChanged = pre.ownerId != post.ownerId
        val possessionFlipped = pre.hasBall != post.hasBall
        return when (actionClass) {
            ActionClass.PASS, ActionClass.SHOT, ActionClass.CROSS, ActionClass.THROUGH ->
                ownerChanged || possessionFlipped || moved > 8f
            ActionClass.MOVE, ActionClass.EVADE ->
                moved > 2f
            ActionClass.KEEPER, ActionClass.DEFEND ->
                moved > 4f || possessionFlipped
            // SWITCH (Phase 5, step 2): a switch moves the cursor,
            // not the ball — no ball-effect expectation, so it shares
            // the NONE arm's generic check.
            ActionClass.NONE, ActionClass.SWITCH, null ->
                moved > 2f || ownerChanged
        }
    }
}
