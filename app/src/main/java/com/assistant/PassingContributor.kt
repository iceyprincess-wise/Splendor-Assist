object PassingContributor : GameplayContributor {
    override val engineName = "Passing"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Remove unnecessary waiting gates
        if (!frame.trusted || !frame.hasBall) return null
        val rx = frame.passTargetX
        val ry = frame.passTargetY
        if (rx <= 0f && ry <= 0f) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = rx,
            targetY = ry,
            authority = frame.bestLaneConfidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
/* ======
PassingContributor Anchor
====== */

/* ========
ReceiverEngagementContributor
======== */
