object TrueCrossContributor : GameplayContributor {
    override val engineName = "TrueCross"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0 && frame.bestLaneConfidence <= 0f) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val receiver = scene?.trackedPlayers
            ?.filter { it.isUserTeam && !it.isGoalkeeper }
            ?.minByOrNull {
                hypot(
                    (it.x - frame.passTargetX).toDouble(),
                    (it.y - frame.passTargetY).toDouble()
                )
            }

        val goalCX = if (frame.goalDetected)
            (frame.goalLeftX + frame.goalRightX) * 0.5f else com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val goalCY = if (frame.goalDetected)
            (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY

        val result = TrueCrossEngine.compute(
            frame.ballX, frame.ballY,
            receiver?.x ?: frame.passTargetX,
            receiver?.y ?: frame.passTargetY,
            receiver?.velocityX ?: 0f,
            receiver?.velocityY ?: 0f,
            goalCX, goalCY,
            frame.bestLaneConfidence.coerceAtLeast(0.1f)
        ) ?: return null

        val boost = if (frame.enabled) 0.14f else 0f
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = result.targetX,
            targetY = result.targetY,
            authority = (result.confidence + boost).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 42L
        )
    }
}
/* ======
TrueCrossContributor Anchor
====== */

/* ========
TruePassContributor
======== */
/**
 * TruePassContributor
 *
 * Upgraded zero-allocation GameplayContributor.
 * - Uses direct indexed array loop over tracked players to eliminate GC allocations.
 * - Leverages TrueTargetPassingEngine.optimizeWithAdvancedTactics for run prediction
 *   and defender density offset.
 */
