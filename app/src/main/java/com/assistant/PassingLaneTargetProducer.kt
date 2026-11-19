package com.assistant

/**
 * B8 — Passing-Lane Target Producer (engine-ideas §2, Q68-4, D8).
 *
 * Bottleneck: passing decisions read a CROSSING target —
 * FrameAssembler.passTarget derived from crossingLaneAnalysis.lanes
 * (best viable crossing lane, target = receiver.y − 40). A pass target
 * is the receiver; the −40 is a crossing lead, not a pass.
 *
 * TRACE: CALLER FrameAssembler.assemble → ENGINE this producer
 * (passing graph, not crossing) → CALLEE none (pure selection) →
 * DATA FLOW best viable passing lane → DECISION receiver position as
 * the target (no −40 lead) → MUTATION RuntimeFrame.passTargetX/Y +
 * passTargetId → CONSUMER Passing / TruePass contributors +
 * RuntimeDecisionLoop.chooseRequest (target tracking) → LIVE EFFECT
 * passes aim at the receiver instead of 40 px above them.
 *
 * The crossing target is untouched: FrameAssembler still derives
 * crossTarget from the crossing lanes (D8 four cross types), so each
 * of the four cross types keeps its own target. Only passTarget moves
 * to the passing graph. Viability uses the same bar as the crossing
 * analysis (not blocked, confidence >= 0.40) so a lane cannot be a
 * pass target while failing as a crossing lane on the same numbers.
 */
object PassingLaneTargetProducer {

    /** A pass target must be at least this confident (same bar as crossing). */
    const val MIN_CONFIDENCE = 0.40f

    data class PassingTarget(
        val targetX: Float,
        val targetY: Float,
        val receiverId: Int,
        val confidence: Float
    )

    /**
     * Best viable passing lane's receiver, or null when no lane is
     * viable. Pure selection over the passing graph; the −40 crossing
     * lead is deliberately NOT applied here.
     */
    fun targetFor(graph: PassingLaneGraph): PassingTarget? {
        var best: PassingTarget? = null
        for (lane in graph.lanes) {
            if (lane.blocked) continue
            if (!lane.receiver.x.isFinite() || !lane.receiver.y.isFinite()) continue
            val confidence = (lane.score * (1f - lane.pressure)).coerceIn(0f, 1f)
            if (confidence < MIN_CONFIDENCE) continue
            if (best == null || confidence > best.confidence) {
                best = PassingTarget(
                    targetX = lane.receiver.x,
                    targetY = lane.receiver.y,
                    receiverId = lane.receiver.id,
                    confidence = confidence
                )
            }
        }
        return best
    }
}
