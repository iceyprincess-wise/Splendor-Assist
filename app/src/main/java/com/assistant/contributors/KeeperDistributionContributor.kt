package com.assistant.contributors

import com.assistant.KeeperDistributionEngine
import com.assistant.KeeperDistributionKind
import com.assistant.runtime.*

/*
 * G-J — Keeper Distribution contributor.
 *
 * After the keeper has the ball (goalkeeper visible, ball within his
 * reach, we have possession): distribute by KeeperDistributionEngine's
 * choice — THROW (short, to the nearest teammate in range when not
 * hard-pressed) or KICK (long, to the farthest-upfield teammate when
 * pressed or nobody is near). PASS-class: the distribution is a pass
 * to a real teammate, with the kind expressed through the target
 * (short vs long) — there is no separate throw/kick button channel in
 * the terminal, and none is invented. HOLD means no safe distribution:
 * silent, the keeper keeps the ball. Authority 0.65 — distribution
 * outranks recycling but never outranks a save.
 */
object KeeperDistributionContributor : GameplayContributor {
    override val engineName = "KeeperDistribution"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.PASSING)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall && frame.goalkeeperVisible

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (!frame.goalkeeperVisible) return null
        val decision = KeeperDistributionEngine.choose(frame)
        if (decision.kind == KeeperDistributionKind.NONE ||
            decision.kind == KeeperDistributionKind.HOLD
        ) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = decision.targetX.coerceIn(0f, frame.captureWidth),
            targetY = decision.targetY.coerceIn(0f, frame.captureHeight),
            authority = 0.65f,
            confidence = frame.confidence,
            durationHintMs = if (decision.kind == KeeperDistributionKind.THROW) 40L else 50L,
            targetEntityId = decision.targetEntityId
        )
    }
}
