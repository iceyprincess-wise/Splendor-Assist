package com.assistant.contributors

import com.assistant.SetPieceEngine
import com.assistant.runtime.*

/*
 * G-A — Set-Piece contributor.
 *
 * Defensive set piece only (opponent's dead ball near our goal): the
 * controlled defender covers the near post (SetPieceEngine's cover
 * point) so the keeper is not left alone on a corner / free kick /
 * penalty. Modest authority (0.60) — a set piece is a moment for
 * shape, not for a hero sprint; the keeper family still outranks it
 * on saves. Silent on attacking set pieces (our corner/free kick):
 * the cross into the box already belongs to CrossDeliveryContributor
 * when a lane is viable, and this contributor invents no second
 * attacking routine. Silent in open play: SetPieceEngine's dead-ball
 * + packed-box gates are the only way in.
 */
object SetPieceContributor : GameplayContributor {
    override val engineName = "SetPiece"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.KEEPER)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall && frame.goalDetected

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (!frame.goalDetected) return null
        val detection = SetPieceEngine.detect(frame)
        if (detection.kind == com.assistant.SetPieceKind.NONE) return null
        if (!detection.defensive) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = detection.coverX.coerceIn(0f, frame.captureWidth),
            targetY = detection.coverY.coerceIn(0f, frame.captureHeight),
            authority = 0.60f,
            confidence = frame.confidence,
            durationHintMs = 40L
        )
    }
}
