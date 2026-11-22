package com.assistant

/**
 * L10 — Learning-Chain Reader (LearningChainReader).
 *
 * Gap: the app computed learning/tactical analysis that no decision ever
 * saw — FrameAssembler read only `crossingLaneAnalysis` + `possession`
 * out of ~50 world-state results (repo map G2). `OnlineParameterAdaptationResult`
 * had zero consumers; RuntimeFrame had no such fields.
 *
 * Contract: in = Phase3WorldStateStore learning results; out =
 * RuntimeFrame fields (opponentBehaviour, playerTendency, shootingHabit,
 * formationAdaptation, offsideRisk, counterattack, + preferredPassing /
 * temporalMemory / onlineParameter); caller = FrameAssembler.assemble();
 * consumer = RuntimeDecisionLoop + the contributors (see below).
 *
 * This object is the single reader: it turns the world-state result
 * objects into plain scalars (LearningReadout) so FrameAssembler stays a
 * thin copy. Every scalar defaults to its "no learning yet" value
 * (0 / false / tickSync 1.0) and every consumer gates on the matching
 * confidence — with no learning the frame behaves exactly as before.
 *
 * Live consumers (this batch):
 *   - ThreatPriorityContributor.threatOf: opponent aggression lifts the
 *     threat heat by at most +0.10, scaled by the learning confidence.
 *   - CrossDeliveryContributor / TrueCrossContributor: crossType (D8)
 *     is carried on the frame; the pass target selection reads it.
 *   - FrameAssembler.frameRuntimeSnapshot: the readout is visible in the
 *     snapshot, so a live session's frame state names the learning values.
 * Phase L: the other 13 readout fields (playerTendency biases, formation
 * score, offside safe lanes, counterattack confidence, preferredPassing,
 * online gain, and the tick-sync multiplier) are DELETED — PROVEN zero
 * readers (computed and never copied onto the frame or read anywhere); the world-
 * state results remain the live source. The tick-sync multiplier's live
 * consumer (item 8) reads the world state directly in
 * ActiveGestureController, not this readout. The 6 fields that remain
 * are the ones FrameAssembler copies onto the frame. The readout is NOT
 * faked: a 0 confidence means "unlearned", never a default behaviour.
 *
 * Pure Kotlin over the world-state data classes (no Android): the
 * offside summary math is pinned in the batch-1 harness (group 20).
 */
data class LearningReadout(
    val opponentAggression: Float = 0f,
    val opponentPressFrequency: Float = 0f,
    val opponentBehaviourConfidence: Float = 0f,
    val offsideRisk: Float = 0f,
    val counterattackDetected: Boolean = false,
    val temporalConfidence: Float = 0f
)

object LearningChainReader {

    /**
     * Offside summary: the worst risk across lanes + how many lanes are
     * safe. An empty lane list reads as risk 0 / 0 safe (no claim), never
     * as a safe pass.
     */
    fun offsideSummary(lanes: List<OffsideRisk>): Pair<Float, Int> {
        if (lanes.isEmpty()) return 0f to 0
        val worst = lanes.maxOf { it.risk }
        val safe = lanes.count { it.safe }
        return worst to safe
    }

    fun read(world: Phase3WorldState): LearningReadout {
        val opponent = world.opponentBehaviourLearningResult
        // Phase L: only the worst risk is used now (the safe-lane count was
        // a dead field — deleted with the other 12 above). offsideSummary
        // itself is untouched (pinned by the batch-1 harness, group 20).
        val (worstRisk, _) = offsideSummary(world.offsideRiskEstimationResult.lanes)
        val counterattack = world.counterattackDetectionResult
        return LearningReadout(
            opponentAggression = opponent.aggression.coerceIn(0f, 1f),
            opponentPressFrequency = opponent.pressFrequency.coerceIn(0f, 1f),
            opponentBehaviourConfidence = opponent.confidence.coerceIn(0f, 1f),
            offsideRisk = worstRisk.coerceIn(0f, 1f),
            counterattackDetected = counterattack.detected,
            temporalConfidence = world.temporalMemoryState.temporalConfidence.coerceIn(0f, 1f)
        )
    }
}
