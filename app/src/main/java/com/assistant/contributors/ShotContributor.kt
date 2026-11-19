package com.assistant.contributors

import com.assistant.ShotTypeEngine
import com.assistant.runtime.*
import kotlin.math.hypot

/*
 * Shot contributor (Task C item (d) - previously deferred, now unblocked).
 *
 * This engine was deliberately NOT onboarded until the frame carried the
 * goal detector's real output. It aims only at coordinates the vision
 * pipeline actually produced:
 *
 *  - fires only when the goal frame is DETECTED this frame (real box, real
 *    confidence), we have the ball, and the ball is within shooting range
 *    of the goal it can see;
 *  - aim point is INSIDE the detected goal mouth, biased toward the half
 *    away from the goalkeeper when the keeper is visible, straight at the
 *    center when not;
 *  - silent otherwise. No goal in view = no shot. Ever.
 */
object ShotContributor : GameplayContributor {
    override val engineName = "Shot"
    override val capabilities = setOf(EngineCapability.ATTACK)

    private const val MIN_GOAL_CONFIDENCE = 0.30f
    // SPLENDOR_P12_T4_SHOT_RANGE: 620px was never normalized, so the
    // effective shooting range changed with capture size. Same 620px at a
    // 1080px-wide capture, scaled by the live capture width.
    private const val MAX_SHOT_RANGE_FRACTION_OF_WIDTH = 620f / 1080f
    private const val MOUTH_INSET = 0.18f // keep the aim inside the posts

        // SPLENDOR_V43_FAST_GATE
    override fun fastGate(frame: RuntimeFrame): Boolean = frame.trusted && frame.hasBall && frame.goalDetected

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // No68-F-D14: contribute() used to depend on fastGate() to make
        // possessionWeight/trustWeight 1.0 (fluidMultiplier was always 1.0
        // via the registry). Enforce the preconditions here instead, so a
        // direct call cannot emit a zero-authority SHOT, and drop the dead
        // weights.
        if (!frame.hasBall || !frame.trusted) return null
        if (frame.confidence < 0.05f) return null
        if (!frame.goalDetected) return null
        if (frame.goalConfidence < MIN_GOAL_CONFIDENCE) return null
        if (frame.goalWidth <= 1f) return null

        val range = hypot(
            frame.goalCenterX - frame.ballX,
            frame.goalCenterY - frame.ballY
        )
        val maxRange = if (frame.captureWidth > 0f) MAX_SHOT_RANGE_FRACTION_OF_WIDTH * frame.captureWidth else 620f
        if (range > maxRange) return null

        // Aim inside the mouth, away from the keeper if we can see one.
        val inset = frame.goalWidth * MOUTH_INSET
        val leftAim = frame.goalLeftX + inset
        val rightAim = frame.goalRightX - inset
        var aimX = when {
            !frame.goalkeeperVisible -> frame.goalCenterX
            frame.goalkeeperX <= frame.goalCenterX -> rightAim
            else -> leftAim
        }
        var aimY = (frame.goalTopY + (frame.goalBottomY - frame.goalTopY) * 0.5f)

        // Closer shot + clearer goal sighting = more authority. A keeper we
        // can actually see (and aim around) is worth more than a blind shot.
        val proximity = (1f - range / maxRange).coerceIn(0f, 1f)
        val keeperBonus = if (frame.goalkeeperVisible) 0.15f else 0f
        var authority =
            (0.35f + 0.35f * proximity + 0.15f * frame.goalConfidence + keeperBonus)
                .coerceIn(0f, 1f)

        // G-G (batch 4): shot-type selection — chip the advanced keeper,
        // power it close in, place it (finesse) from range. The type
        // moves the aim INSIDE the mouth only and adjusts the duration.
        // The reassigned aimX/aimY keep the No68-F-D34 clamp text below
        // (targetX = aimX.coerceIn(...)) literal, so that pin still
        // guards the clamp; the clamp itself is unchanged.
        val shotType = ShotTypeEngine.select(frame, aimX, aimY)
        aimX = shotType?.aimX ?: aimX
        aimY = shotType?.aimY ?: aimY
        val durationHint = shotType?.durationHintMs ?: 30L

        // G-C (batch 4, D12): match-state posture — chase a goal late
        // (shoot more), protect a lead late (shoot less). Only when the
        // state is known; an unknown clock changes nothing.
        if (frame.matchStateKnown && frame.matchPhase == MatchPhase.LATE) {
            authority = when {
                frame.matchScoreFor < frame.matchScoreAgainst -> (authority + 0.10f).coerceIn(0f, 1f)
                frame.matchScoreFor > frame.matchScoreAgainst -> (authority - 0.05f).coerceIn(0f, 1f)
                else -> authority
            }
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            // No68-F-D34 (completion round): the aim was clamped only
            // >= 0 — the same one-sided clamp D5 fixed in
            // ShotAnticipationContributor. The goal box can extend past
            // the capture edge (the goal sits at an X end), so rightAim /
            // aimY could exceed captureWidth/captureHeight and the
            // dispatch would aim off-screen. Clamp both axes to the
            // capture, same form as D5; unchanged for any on-screen goal.
            targetX = aimX.coerceIn(0f, frame.captureWidth),
            targetY = aimY.coerceIn(0f, frame.captureHeight),
            authority = authority,
            // No68-F-D13 (closes Q67-18): the product double-discounted
            // Shot against TrueShot (ConAI scores authority^2 x
            // confidence, and frame/goal confidence are not independent —
            // the goal sighting is part of the frame sighting). Weakest
            // link: min(...).
            confidence = minOf(frame.confidence, frame.goalConfidence).coerceIn(0f, 1f),
            durationHintMs = durationHint
        )
    }
}
