package com.assistant

import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

object KickingPostureContributor : GameplayContributor {
    override val engineName = "KickingPosture"
    // No68-I-D4: only PASS/SHOT are ever emitted below; DEFENSE was a
    // decorative capability value. (Capabilities are read by
    // GameplayEngineRegistry diagnostics, batch 5b Q68-75.)
    override val capabilities = setOf(
        EngineCapability.PASSING,
        EngineCapability.ATTACK
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (!NativeBridge.isNativeReady()) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var carrier: TrackedPlayer? = null
        var minCarrierDist = Float.MAX_VALUE

        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam && !p.isGoalkeeper) {
                val d = hypot((p.x - frame.ballX).toDouble(), (p.y - frame.ballY).toDouble()).toFloat()
                if (d < minCarrierDist) {
                    minCarrierDist = d
                    carrier = p
                }
            }
        }

        val userX = carrier?.x ?: frame.ballX
        val userY = carrier?.y ?: frame.ballY
        val userVx = carrier?.velocityX ?: 0f
        val userVy = carrier?.velocityY ?: 0f

        // No68-I-D1: a SHOT must aim at the goal, not at the pass target or
        // the ball. The old code aimed every SHOT at passTarget (or the ball
        // when no lane existed) — the corrected point then rotated up to 25
        // degrees away from a target that was never the goal in the first
        // place. TrueShot (the senior SHOT contributor) aims from the goal
        // bounds; this contributor now aims at the goal centre the same way.
        val isShot = frame.goalDetected
        val targetX = if (isShot) frame.goalCenterX else if (frame.passTargetX > 0f) frame.passTargetX else frame.ballX
        val targetY = if (isShot) frame.goalCenterY else if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        val kickingBuffer = FloatArray(4)
        // Do not read the buffer when the native library is not loaded:
        // it would still be all zeros, and the code below would emit a
        // live PASS/SHOT at (0, 0) with 0.85 authority from fabricated data.
        if (!NativeBridge.nativeKickingPosture(userX, userY, userVx, userVy, targetX, targetY, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), kickingBuffer)) return null
        // No68-I-D38: the native impl returns void, so a bail-out inside it
        // (bad array, non-finite input) leaves the buffer unwritten — all
        // zeros. A real computation can never produce (0, 0, 0, 0): a zero
        // balance needs 180 deg misalignment, which always sets
        // requiresAdjustTouch (buffer[3] = 1). All zeros means "not written".
        if (kickingBuffer[0] == 0f && kickingBuffer[1] == 0f && kickingBuffer[2] == 0f && kickingBuffer[3] == 0f) return null
        val result = PostureCorrectionResult(
            correctedX = kickingBuffer[0],
            correctedY = kickingBuffer[1],
            balanceScore = kickingBuffer[2],
            requiresAdjustTouch = kickingBuffer[3] > 0.5f
        )

        // No68-I-D2: the native code only corrects when misalignment exceeds
        // 60 deg, while this contributor used to fire from 51.75 deg (balance
        // < 0.95). In the 51.75-60 deg gap the "corrected" point IS the target,
        // so the old code emitted an uncorrected duplicate at the floored 0.3
        // authority. No correction applied -> no contribution. This also
        // subsumes the old balance >= 0.95 early return (that band never
        // corrects), including its vacuous `!requiresAdjustTouch` clause
        // (No68-I-D3: requiresAdjust implies balance <= 0.52).
        if (result.correctedX == targetX && result.correctedY == targetY) {
            return null
        }

        val action = if (isShot) ActionClass.SHOT else ActionClass.PASS
        val authority = (1f - result.balanceScore).coerceIn(0.3f, 0.85f)

        // No68-I-D1 (continued): the native correction rotates the aim up to
        // 25 deg toward the carrier's facing. At shooting distance that can
        // push the aim off the mouth entirely. Keep the correction's
        // direction, but clamp the corrected point back inside the goal box
        // so a posture-fixed shot still lands on the goal.
        val outX = if (isShot) result.correctedX.coerceIn(frame.goalLeftX, frame.goalRightX) else result.correctedX
        val outY = if (isShot) result.correctedY.coerceIn(frame.goalTopY, frame.goalBottomY) else result.correctedY

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = outX,
            targetY = outY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = if (result.requiresAdjustTouch) 60L else 32L
        )
    }
}
