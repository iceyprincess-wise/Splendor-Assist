package com.assistant.contributors

import com.assistant.CombinationPlayEngine
import com.assistant.runtime.*

/*
 * G-L — Combination Play contributor (one-two, first step only).
 *
 * The multi-step offer (pass + run + return) needs an arbitration
 * extension the single-offer arbiter does not have — that extension
 * is NOT built here (stated in CombinationPlayEngine, not assumed).
 * What this contributor offers is the FIRST STEP as an ordinary
 * single PASS: the ball to the runner's projected position, when a
 * teammate is running upfield with a defender line between ball and
 * runner. When the runner receives, the next frame re-plans — the
 * return is then the ordinary best pass or the next one-two step.
 * Authority 0.55: a combination beats a sideways recycle, but a
 * clean shot or a lane pass still outranks it.
 */
object CombinationPlayContributor : GameplayContributor {
    override val engineName = "CombinationPlay"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val plan = CombinationPlayEngine.plan(frame)
        if (!plan.viable) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = plan.targetX.coerceIn(0f, frame.captureWidth),
            targetY = plan.targetY.coerceIn(0f, frame.captureHeight),
            authority = 0.55f,
            confidence = frame.confidence,
            durationHintMs = 45L,
            targetEntityId = plan.runnerId
        )
    }
}
