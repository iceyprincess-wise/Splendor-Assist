package com.assistant.contributors

import com.assistant.DefensiveStanceProducer
import com.assistant.runtime.*

/*
 * Midfield hold contributor (queue item 5, batch midfield).
 *
 * Drives the controlled player back to the midfield line when the
 * midfield is BROKEN (stretched past the tight shape Ed set in D14),
 * so the team holds its shape instead of chasing. Follows the
 * Marking pattern exactly: reads the published frame fields, resolves
 * the controlled id, emits ONE EngineContribution through the ordinary
 * contributor path — the app drives ONE controlled player and there is
 * no second input stream, so the hold steers that player.
 *
 * Gate (ALL must hold): frame trusted, we do NOT have the ball, and
 * a midfield is known — the MEASURED line (MidfieldLineEngine, via
 * the frame) is primary; the STANCE-derived midfield
 * (DefensiveStanceProducer, the D14 numbers wired onto the frame by
 * FrameAssembler) is the fallback when no measured line was found
 * this frame. BROKEN is either half:
 *   - spread: the midfield spread exceeds the capture width times
 *     DefensiveStanceProducer.MAX_MIDFIELD_SPREAD_FRACTION — the D14
 *     0.30 constant, imported, never re-tuned and never duplicated.
 *   - compactness: the midfield compactness exceeds 0.55 with a
 *     non-zero compactness confidence. 0.55 is the spread-out bar the
 *     pressing recogniser already uses for this same native quantity
 *     (PressingRecognitionEngine.kt:136) — reused here as stated
 *     tuning, not a new threshold. The confidence leg keeps a
 *     zero-confidence reading from holding the player.
 * Both halves are live readers of their fields — that is what makes
 * the compactness engine fully effective, not log-only.
 *
 * Target: X = the midfield line averageX clamped into [minX, maxX]
 * (the HomePositionProducer pattern — return to the tight line in
 * your own lateral lane); in fallback, X = the stance-derived
 * midfieldX (already clamped to the capture by the producer). Y =
 * the controlled player's CURRENT Y (lane preserved, not invented):
 * the controlled entity is frame.defenderId when the world store
 * names one, else the teammate nearest the ball (the Marking
 * pattern); with no teammate on the frame there is nobody to steer
 * and the contributor offers nothing.
 *
 * Class is DEFEND, not a movement-class offer: the arbitration trace
 * (ConAIEngine) proves a movement-class offer while defending wins
 * only when no DEFEND/KEEPER offer has authority >= 0.25 and is then
 * scaled 0.35 — a hold that must move the defender while defending
 * has to be DEFEND. Authority is 0.60f FIXED (stated unmeasured
 * tuning, needs live-play calibration): it beats Marking's 0.55, and
 * loses to Defense whenever defenderDensity > 0.60. confidence is
 * the frame confidence; durationHintMs is 40L (Marking precedent).
 */
object MidfieldHoldContributor : GameplayContributor {
    override val engineName = "MidfieldHold"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall &&
            (frame.midfieldFound || frame.stanceMidfieldFound)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return null
        val measured = frame.midfieldFound
        if (!measured && !frame.stanceMidfieldFound) return null
        val spread = if (measured) frame.midfieldSpread else frame.stanceMidfieldSpread
        val brokenBySpread = spread >
            frame.captureWidth * DefensiveStanceProducer.MAX_MIDFIELD_SPREAD_FRACTION
        val brokenByCompactness = measured &&
            frame.midfieldCompactness > 0.55f && frame.midfieldConfidence > 0f
        if (!brokenBySpread && !brokenByCompactness) return null
        val controlled = frame.entities.firstOrNull { it.id == frame.defenderId && it.teammate }
            ?: frame.entities.filter { it.teammate }.minByOrNull { e ->
                (e.x - frame.ballX) * (e.x - frame.ballX) +
                    (e.y - frame.ballY) * (e.y - frame.ballY)
            } ?: return null
        val targetX = if (measured) {
            frame.midfieldLineX.coerceIn(frame.midfieldMinX, frame.midfieldMaxX)
                .coerceIn(0f, frame.captureWidth)
        } else {
            frame.stanceMidfieldX.coerceIn(0f, frame.captureWidth)
        }
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = targetX,
            targetY = controlled.y.coerceIn(0f, frame.captureHeight),
            authority = 0.60f,
            confidence = frame.confidence,
            durationHintMs = 40L,
            targetEntityId = controlled.id
        )
    }
}
