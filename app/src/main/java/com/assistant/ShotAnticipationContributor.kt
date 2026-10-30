object ShotAnticipationContributor : GameplayContributor {
    override val engineName = "ShotAnticipation"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.KEEPER)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity < 0.4f) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            frame.ballX, (frame.ballY - 60f).coerceAtLeast(0f),
            (frame.defenderDensity * 0.85f).coerceIn(0f, 1f), frame.confidence, 30L)
    }
}
/* ======
ShotAnticipationContributor Anchor
====== */

/* ========
KickingPostureContributor
======== */
