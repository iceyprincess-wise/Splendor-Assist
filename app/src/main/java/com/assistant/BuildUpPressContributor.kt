package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object BuildUpPressContributor : GameplayContributor {
    override val engineName   = "BuildUpPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // No68-I-D15: `nativeLoaded` can be false while the native library is
        // loaded (TrainedDetectionEngine marks preprocess unavailable without
        // clearing `nativeReady`), which used to silence this contributor
        // while KickingPosture (isNativeReady-gated) kept working.
        if (!NativeBridge.isNativeReady()) return null

        // No68-I-D17 / No68-I-D18: this contributor was strictly dominated by
        // InstantIntercept — identical native firing gates, authority 1.0,
        // same action/confidence, later registration — so it could never win
        // arbitration, and it never read its namesake detector. It now fires
        // ONLY on frames where a build-up is actually recognised; the
        // detector is the router, and InstantIntercept defers on those same
        // frames, so exactly one press owns each frame. If the world store
        // is unreadable the press is left to InstantIntercept (safe side).
        val buildUp = try { Phase3WorldStateStore.current().buildUpRecognitionResult } catch (_: Throwable) { null }
        if (buildUp?.detected != true) return null

        val ownership = try { Phase3WorldStateStore.current().ownership } catch(_:Throwable) { com.assistant.BallOwnershipResult(false) }
        val owner = ownership.owner

        // No68-I-D16: the shared mutable nativeBuffer is now a per-call local
        // (No67-D16/D17/D26/D34 precedent). A fresh zeroed buffer also closes
        // the C early-return stale hole: an unwritten buffer reads [0] = 0.
        val nativeBuffer = FloatArray(4)
        NativeBridge.nativeBuildUpPressCompute(
            frame.hasBall,
            frame.trusted,
            frame.confidence,
            ownership.hasOwner,
            owner?.x ?: 0f,
            owner?.y ?: 0f,
            owner?.isUserTeam ?: false,
            frame.ballX,
            frame.ballY,
            // No68-I-D20: the C code used to clamp to a hardcoded 1650x720.
            // Pass the real capture dimensions instead.
            com.assistant.vision.CameraProfile.captureWidthOrFallback(),
            com.assistant.vision.CameraProfile.captureHeightOrFallback(),
            nativeBuffer
        )

        if (nativeBuffer[0] <= 0.5f) return null
        // No68-I-D21: native_gameplay_compute.c does no finite-input check
        // (contrast native_kicking_posture.c's splendor_finite). A NaN target
        // would only be dropped later by the registry; reject it here.
        if (!nativeBuffer[1].isFinite() || !nativeBuffer[2].isFinite() || !nativeBuffer[3].isFinite()) return null

        return EngineContribution(engineName, ActionClass.DEFEND,
            nativeBuffer[1], nativeBuffer[2], nativeBuffer[3], frame.confidence, 20L)
    }
}

/* ========
CrossContributor
======== */
