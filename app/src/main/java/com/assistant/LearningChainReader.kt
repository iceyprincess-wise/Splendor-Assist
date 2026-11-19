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
 * Remaining fields (playerTendency biases, formation score, offside risk,
 * counterattack, preferredPassing, temporalConfidence, online gain) are
 * carried on the frame with no consumer yet — their consumers are the
 * next round's work, and the frame is the seam they will read. The
 * readout is NOT faked: a 0 confidence means "unlearned", never a
 * default behaviour.
 *
 * Pure Kotlin over the world-state data classes (no Android): the
 * offside summary math is pinned in the batch-1 harness (group 20).
 */
data class LearningReadout(
    val opponentAggression: Float = 0f,
    val opponentPressFrequency: Float = 0f,
    val opponentBehaviourConfidence: Float = 0f,
    val playerPassBias: Float = 0f,
    val playerDribbleBias: Float = 0f,
    val playerShootBias: Float = 0f,
    val playerTendencyConfidence: Float = 0f,
    val shootingHabitConfidence: Float = 0f,
    val formationAdaptationScore: Float = 0f,
    val formationStable: Boolean = false,
    val formationConfidence: Float = 0f,
    val offsideRisk: Float = 0f,
    val offsideSafeLanes: Int = 0,
    val counterattackDetected: Boolean = false,
    val counterattackConfidence: Float = 0f,
    val preferredPassingConfidence: Float = 0f,
    val temporalConfidence: Float = 0f,
    val onlineAdaptationGain: Float = 0f,
    val tickSyncMultiplier: Float = 1f
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
        val tendency = world.playerTendencyLearningResult
        val shooting = world.shootingHabitLearningResult
        val formation = world.formationAdaptationResult
        val (worstRisk, safeLanes) = offsideSummary(world.offsideRiskEstimationResult.lanes)
        val counterattack = world.counterattackDetectionResult
        val online = world.onlineParameterAdaptationResult
        return LearningReadout(
            opponentAggression = opponent.aggression.coerceIn(0f, 1f),
            opponentPressFrequency = opponent.pressFrequency.coerceIn(0f, 1f),
            opponentBehaviourConfidence = opponent.confidence.coerceIn(0f, 1f),
            playerPassBias = tendency.passBias.coerceIn(0f, 1f),
            playerDribbleBias = tendency.dribbleBias.coerceIn(0f, 1f),
            playerShootBias = tendency.shootBias.coerceIn(0f, 1f),
            playerTendencyConfidence = tendency.confidence.coerceIn(0f, 1f),
            shootingHabitConfidence = shooting.confidence.coerceIn(0f, 1f),
            formationAdaptationScore = formation.adaptationScore.coerceIn(0f, 1f),
            formationStable = formation.formationStable,
            formationConfidence = formation.confidence.coerceIn(0f, 1f),
            offsideRisk = worstRisk.coerceIn(0f, 1f),
            offsideSafeLanes = safeLanes,
            counterattackDetected = counterattack.detected,
            counterattackConfidence = counterattack.confidence.coerceIn(0f, 1f),
            preferredPassingConfidence = world.preferredPassingLaneLearningResult.confidence.coerceIn(0f, 1f),
            temporalConfidence = world.temporalMemoryState.temporalConfidence.coerceIn(0f, 1f),
            onlineAdaptationGain = online.adaptationGain.coerceIn(0f, 1f),
            tickSyncMultiplier = if (online.tickSyncMultiplier.isFinite() && online.tickSyncMultiplier > 0f)
                online.tickSyncMultiplier else 1f
        )
    }
}
