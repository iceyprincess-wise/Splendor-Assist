package com.assistant





































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object DefenseContributor : GameplayContributor {
    override val engineName = "Defense"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    private val outStopper = FloatArray(4)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // No68-D-D1: gated like every other DEFEND contributor. The old
        // "UNCONDITIONAL AGGRESSIVE EXECUTION - NO GATES" fired a DEFEND
        // drag while WE had the ball (ConAI hasBall arm, bar 0.30), on
        // untrusted frames, and with no opponent visible at all
        // (defenderDensity == 0). A defensive claim with nothing to
        // defend against is noise that can outrank a real attack.
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity <= 0f) return null

        // No68-H-D32: the dominance-matrix call is deleted — its outputs
        // were 100% discarded (outDominance never read), so the JNI call
        // burned a crossing + math every frame for zero live effect. The
        // strobe/tackle concept has no tap consumer to wire to (QH-4).
        //
        // No68-D-D4: nativeComputeDominanceVectors REMOVED (proven dead,
        // confirmed unwireable — see defects.md No68-D-D4). Its outputs
        // were computed every frame and never read: the strobe joystick
        // target conflicts with the stopper target (only one drag can
        // win), the tackle tap has no EngineContribution channel, and
        // its duration duplicates the stopper's. Removing the call also
        // removes one JNI round-trip per frame.
        //
        // No68-D-D3: the stopper matrix takes the DEFENDER's position, not
        // a hardcoded 50 px offset from the ball. Use the tracked defender
        // entity (the same id chooseRequest() uses as the drag origin);
        // fall back to the historical ball-50 position when no defender
        // is tracked.
        // MERGE (no68): D's "Velocities stay 0" sentences in No68-D-D3 are
        // SUPERSEDED by the synthesis ruling — H wins: real per-second
        // velocities below (No68-H-D39).
        //
        // No68-H-D39: defender position/velocity and ball velocity are the
        // frame's real values now — defX was ballX-50 fabricated and every
        // velocity was 0f (so the native lead was always dead).
        val defender = frame.entities.firstOrNull { it.id == frame.defenderId && it.teammate }
        val defX = defender?.x ?: (frame.ballX - 50.0f)
        val defY = defender?.y ?: frame.ballY
        val defVx = defender?.velocityXPerSecond ?: 0f
        val defVy = defender?.velocityYPerSecond ?: 0f
        val screenW = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val screenH = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        // No68-H-D33: the native stick rest is the trained joystick centre.
        val restX = screenW * ControlMappingTrainer.joystickCenterX()
        val restY = screenH * ControlMappingTrainer.joystickCenterY()

        // No68-H-D38: isHoldingPressure was frame.hasBall — inverted
        // (pressure applied while attacking, absent while defending). The
        // real signal is the user's touch, same as DashPressureContributor.
        val holdingPressure = UserTouchState.isTouching()

        // No68-H-D40: the bare external gives no success signal — a C
        // contract rejection throws at the call, and a failed critical
        // array leaves the previous frame's values in the buffer (the
        // F1/F2 pattern). Guard readiness, zero the outputs first, and
        // verify finite results before trusting them.
        outStopper.fill(0f)
        val ok = try {
            if (!com.assistant.NativeBridge.isNativeReady()) false
            else {
                com.assistant.NativeBridge.nativeExecuteStopperMatrix(
                    defX, defY, defVx, defVy,
                    // L14 (batch 1): the home anchor is the producer's
                    // home (frame.homeX/homeY, HomePositionProducer via
                    // VisionCore + FrameAssembler) when the frame carries
                    // one; screen centre is the fallback when no line was
                    // found (homeFound=false). QH-6 closed.
                    if (frame.homeFound) frame.homeX else screenW * 0.5f,
                    if (frame.homeFound) frame.homeY else screenH * 0.5f,
                    frame.ballX, frame.ballY, frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond,
                    holdingPressure, screenW, screenH,
                    restX, restY,
                    outStopper
                )
                true
            }
        } catch (_: Throwable) {
            false
        }
        if (!ok || !outStopper[0].isFinite() || !outStopper[1].isFinite()) return null

        val finalX = outStopper[0].coerceAtLeast(0f)
        val finalY = outStopper[1].coerceAtLeast(0f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = finalX,
            targetY = finalY,
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            // No68-H-D34: the native pacing duration (12–40ms; 12ms in
            // close combat) was discarded for a hardcoded 30L. Wire it —
            // chooseRequest clamps the final duration to 15–85ms anyway.
            // outStopper[2] (barge flag) still has no tap consumer (QH-5).
            durationHintMs = if (outStopper[3].isFinite()) outStopper[3].toLong().coerceIn(15L, 85L) else 30L
        )
    }
}

/* ========
EvadeContributor
======== */
