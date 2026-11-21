package com.assistant.contributors

import com.assistant.BallOwnershipEngine
import com.assistant.CutbackAnticipationEngine
import com.assistant.OpponentPassLaneEngine
import com.assistant.runtime.*
import kotlin.math.*

/*
 * Anticipation contributor (queue item 11).
 *
 * The don't-wait instinct: pre-move the controlled player to where the
 * opponent's play is GOING before it happens, instead of reacting to
 * where it is. Two offers, one contributor, cutback first:
 *   - F4 cutback cover: while the carrier drags a defender laterally,
 *     the controlled player is already moving into the channel on the
 *     opposite side — the space the cut will open. An imminent 1v1
 *     outranks a lane.
 *   - F3 lane intercept: the best reachable opponent pass lane (our
 *     controlled defender gets there with the full safe buffer to
 *     spare), pre-moving to the intercept point before the pass is
 *     played — including through-pass lanes, whose receiver runs into
 *     space behind our line.
 * Both offers need the carrier to HAVE the ball (within the repo's
 * 60 px possession rule); a pass already in flight is
 * InstantIntercept's job (F2), not this contributor's — the gates
 * separate them, so the two never compete on the same frame.
 *
 * Class is DEFEND (the midfield-hold pattern: a DEFEND offer while
 * defending; a movement-class offer would be scaled down by the DEFEND
 * arm). Authority is 1.0f on the narrow confirmed gate (item-10 F3
 * precedent: authority 1.0 on a confirmed threat — the gate IS the
 * safety net). Confidence is the frame confidence; durationHintMs is
 * 40L (InterceptMatrix precedent). One target, one joystick: the
 * contributor emits ONE EngineContribution through the ordinary
 * contributor path, and the arbitration trace (ConAIEngine) decides
 * whether it wins. At defenderDensity == 1.0 the tie goes to the
 * earlier-registered offer (Defense, InterceptMatrix, WingBlock,
 * InstantIntercept all precede Anticipation) — all intercept/defend
 * offers anyway.
 *
 * The keeper is never the dragged line defender: in a 1v1 he would be
 * the nearest user entity to the carrier, which would make the cover
 * target degenerate (midpoint to himself). He is excluded from the
 * dragged search by position (nearest user entity to the visible
 * keeper position); the lane intercept's nearest-gate then still
 * protects the controlled defender from a worse run.
 */
object AnticipationContributor : GameplayContributor {
    override val engineName = "Anticipation"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    // Detection-identity tolerance for the keeper entity (see below).
    private const val KEEPER_IDENTITY_RADIUS_PX = 20f

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (!frame.captureWidth.isFinite() || !frame.captureHeight.isFinite() ||
            frame.captureWidth <= 0f || frame.captureHeight <= 0f
        ) return null

        // The carrier: nearest opponent to the ball, owning it within
        // the repo's 60 px possession rule (WingBlock pattern). No
        // carrier with the ball => no lane to intercept, no drag to
        // read — the in-flight case belongs to InstantIntercept (F2).
        val carrier = frame.entities.filter { !it.teammate }
            .minByOrNull { e ->
                (e.x - frame.ballX) * (e.x - frame.ballX) +
                    (e.y - frame.ballY) * (e.y - frame.ballY)
            } ?: return null
        val carrierDist = hypot(carrier.x - frame.ballX, carrier.y - frame.ballY)
        if (carrierDist > BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE) return null

        val teammates = frame.entities.filter { it.teammate }
        if (teammates.isEmpty()) return null
        // The keeper entity (when visible): the user entity nearest the
        // keeper position — but ONLY when he is close to it (within
        // KEEPER_IDENTITY_RADIUS_PX). The keeper position and the keeper
        // entity come from the same SceneTracker detection, so they
        // coincide within a few px; without the radius, a frame with no
        // keeper entity would misidentify the nearest real defender as
        // the keeper and exclude him from the dragged search. The radius
        // is a detection-identity tolerance, not a tactical number.
        val keeperId = if (frame.goalkeeperVisible) {
            val nearestKeeper = teammates.minByOrNull { e ->
                (e.x - frame.goalkeeperX) * (e.x - frame.goalkeeperX) +
                    (e.y - frame.goalkeeperY) * (e.y - frame.goalkeeperY)
            }
            nearestKeeper?.takeIf { e ->
                hypot(e.x - frame.goalkeeperX, e.y - frame.goalkeeperY) <=
                    KEEPER_IDENTITY_RADIUS_PX
            }?.id
        } else null
        val outfield = if (keeperId != null) teammates.filter { it.id != keeperId } else teammates

        // F4 cutback cover first — an imminent 1v1 outranks a lane.
        val dragged = outfield
            .filter { e ->
                hypot(e.x - carrier.x, e.y - carrier.y) <=
                    BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE
            }
            .minByOrNull { e -> hypot(e.x - carrier.x, e.y - carrier.y) }
        if (dragged != null) {
            val cover = CutbackAnticipationEngine.analyze(
                carrier.velocityXPerSecond, carrier.velocityYPerSecond,
                dragged, outfield,
                frame.goalkeeperX, frame.goalkeeperY, frame.goalkeeperVisible,
                frame.ballX, frame.attackDirection,
                frame.captureWidth, frame.captureHeight
            )
            if (cover != null) {
                return EngineContribution(
                    engine = engineName,
                    actionClass = ActionClass.DEFEND,
                    targetX = cover.targetX,
                    targetY = cover.targetY,
                    authority = 1.0f,
                    confidence = frame.confidence,
                    durationHintMs = 40L
                )
            }
        }

        // F3 lane intercept — the controlled defender pre-moves to where
        // the pass is GOING.
        val controlled = frame.entities.firstOrNull { it.id == frame.defenderId && it.teammate }
            ?: return null
        val receivers = frame.entities.filter { !it.teammate && it.id != carrier.id }
        val lanes = OpponentPassLaneEngine.analyze(carrier.x, carrier.y, receivers, controlled)
        val best = lanes.firstOrNull { it.reachable } ?: return null
        // Nearest gate: the controlled defender must be the nearest user
        // player to the intercept point, else another man is better
        // placed and no offer is made on that lane.
        val nearest = teammates.minByOrNull { e ->
            (e.x - best.interceptX) * (e.x - best.interceptX) +
                (e.y - best.interceptY) * (e.y - best.interceptY)
        }
        if (nearest?.id != controlled.id) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = best.interceptX,
            targetY = best.interceptY,
            authority = 1.0f,
            confidence = frame.confidence,
            durationHintMs = 40L
        )
    }
}
