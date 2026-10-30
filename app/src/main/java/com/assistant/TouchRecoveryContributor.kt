object TouchRecoveryContributor : GameplayContributor {
    override val engineName = "TouchRecovery"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = TouchRecoveryEngine.recover(pressure, strength)

        // After recovering the touch, drive toward the open passing lane.
        // Returning to ballX/ballY (where the player already is) is a no-op.
        val targetX = when {
            frame.viableLaneCount > 0 && frame.passTargetX > 0f -> frame.passTargetX
            else -> (frame.ballX + 60f).coerceIn(0f, 1920f)
        }
        val targetY = when {
            frame.viableLaneCount > 0 && frame.passTargetY > 0f -> frame.passTargetY
            else -> frame.ballY
        }

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.MOVE,
            targetX        = targetX,
            targetY        = targetY,
            authority      = (result.recoveryBoost / 10f).coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 35L
        )
    }
}
/* ======
TouchRecoveryContributor Anchor
====== */

/* ========
TrueCrossContributor
======== */
/**
 * TrueCrossContributor
 *
 * Run-predicted cross: aims at where the receiver WILL BE when the ball arrives.
 *
 * SA cross interception root cause:
 *   SA aims at the receiver's CURRENT position. The receiver is always running
 *   into the box. By the time the lofted ball arrives, the receiver has moved
 *   on and a defender has filled that spot — intercepted every time.
 *
 * TrueCross leads the receiver run so the ball meets them in motion.
 * +0.14 authority boost when SmartAssist corrections are active.
 */
