package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object InstantInterceptContributor : GameplayContributor {
    override val engineName   = "InstantIntercept"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // No68-I-D15: see BuildUpPressContributor — `nativeLoaded` can be
        // false while the native library is loaded; isNativeReady is the
        // correct gate.
        if (!NativeBridge.isNativeReady()) return null

        // No68-I-D17: router half — on build-up frames the press belongs to
        // BuildUpPress (which fires only there). InstantIntercept owns every
        // other !hasBall frame, so the two no longer tie at authority 1.0
        // with InstantIntercept winning by registration order.
        val buildUp = try { Phase3WorldStateStore.current().buildUpRecognitionResult } catch (_: Throwable) { null }
        if (buildUp?.detected == true) return null

        val ownership = try { Phase3WorldStateStore.current().ownership } catch(_:Throwable) { com.assistant.BallOwnershipResult(false) }
        val owner = ownership.owner

        // No68-I-D16: per-call local buffer (see BuildUpPressContributor).
        val nativeBuffer = FloatArray(5)
        NativeBridge.nativeInstantInterceptCompute(
            frame.hasBall,
            frame.trusted,
            frame.confidence,
            ownership.hasOwner,
            owner?.x ?: 0f,
            owner?.y ?: 0f,
            owner?.velocityX ?: 0f,
            owner?.velocityY ?: 0f,
            owner?.isUserTeam ?: false,
            frame.ballX,
            frame.ballY,
            // No68-I-D20: the C code used to guess the screen size from the
            // coordinate magnitude (1650x720 vs 288x640). Pass the real
            // capture dimensions instead.
            com.assistant.vision.CameraProfile.captureWidthOrFallback(),
            com.assistant.vision.CameraProfile.captureHeightOrFallback(),
            nativeBuffer
        )

        if (nativeBuffer[0] <= 0.5f) return null
        // No68-I-D21: finite check (see BuildUpPressContributor). Note
        // buffer[4] (predicted distance) is still computed by the native
        // code and still unread here — a distance gate needs a threshold,
        // which is Ed's call (No68-I-D19).
        if (!nativeBuffer[1].isFinite() || !nativeBuffer[2].isFinite() || !nativeBuffer[3].isFinite()) return null

        return EngineContribution(engineName, ActionClass.DEFEND,
            nativeBuffer[1], nativeBuffer[2], nativeBuffer[3], frame.confidence, 16L)
    }
}

/* ========
InterceptMatrixContributor
======== */
