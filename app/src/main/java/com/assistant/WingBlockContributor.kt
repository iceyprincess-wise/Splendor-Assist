package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object WingBlockContributor : GameplayContributor {
    override val engineName = "WingBlock"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.opponentCount <= 0) return null
        val w = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val h = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        if (w <= 0f || h <= 0f) return null

        // Queue item 10 (F2): the winger is a REAL man, not the ball.
        // Carrier = the opponent nearest the ball; he owns it within
        // 60 px (BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE, the repo's
        // own possession rule). Loose ball (no opponent within 60 px):
        // the ball itself is the target, with its own per-second
        // velocity — the full-back gets to the loose ball first.
        val carrier = frame.entities.filter { !it.teammate }
            .minByOrNull { e -> hypot(e.x - frame.ballX, e.y - frame.ballY) }
        val carrierDist = carrier?.let { e -> hypot(e.x - frame.ballX, e.y - frame.ballY) } ?: Float.MAX_VALUE
        val wx: Float
        val wy: Float
        val wvx: Float
        val wvy: Float
        if (carrier != null && carrierDist <= BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE) {
            wx = carrier.x
            wy = carrier.y
            wvx = carrier.velocityXPerSecond
            wvy = carrier.velocityYPerSecond
        } else {
            wx = frame.ballX
            wy = frame.ballY
            wvx = frame.ballVelocityXPerSecond
            wvy = frame.ballVelocityYPerSecond
        }

        // Queue item 10 (F3/F4): the danger gate. The block fires only
        // in OUR defensive third (own-third convention, by
        // attackDirection — same as BallOnlyDetector / ClearanceEngine),
        // EXCEPT on an overloaded flank: when the threatened flank's
        // wing advantage is < 0 (opponents outnumber us there — the
        // first live consumer of frame.left/rightWingAdvantage, until
        // now log-only), the gate widens to our defensive HALF (W/2 on
        // the attack axis): the full-back steps up EARLIER on the
        // outnumbered wing, meeting the carrier before the final third.
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val inOwnThird = if (attackSign > 0) wx < w / 3f else wx > 2f * w / 3f
        val inOwnHalf = if (attackSign > 0) wx < w / 2f else wx > w / 2f
        val flankAdvantage = when {
            wy < h * 0.15f -> frame.leftWingAdvantage
            wy > h * 0.85f -> frame.rightWingAdvantage
            else -> 0f
        }
        val overloadedFlank = flankAdvantage < 0f
        if (!inOwnThird && !(overloadedFlank && inOwnHalf)) return null

        val result = WingBlockEngine.calculateWingBlockVector(
            wingerX = wx,
            wingerY = wy,
            wingerVx = wvx,
            wingerVy = wvy,
            pitchHeight = h
        ) ?: return null

        // Queue item 10 (F3): authority 1.0 on a CONFIRMED flank threat.
        // The DEFEND arm scores authority^2 x confidence, and Defense
        // offers the same defenderDensity authority as the old block, so
        // the old block could never win a tie (Defense is registered
        // first). A winger with the ball on the flank in our third is
        // the most dangerous threat on the pitch; the generic stopper
        // chases the ball centrally while the cross comes from the
        // flank. Safety nets: at defenderDensity == 1.0 the tie goes to
        // Defense, and a full-authority keeper claim (CrossClaim,
        // registered earlier) wins ties too. Precedence call, for Ed:
        // this also outranks the item-9 drag-out recovery while the
        // carrier threat is live — the cross is imminent, the recovery
        // resumes the frame the threat passes.
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = result.targetX.coerceIn(0f, w),
            targetY = result.targetY.coerceIn(0f, h),
            authority = 1.0f,
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}

/* ========
SharpTouchTimingContributor
======== */
