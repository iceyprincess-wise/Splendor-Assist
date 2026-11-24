package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object DefensiveCompactnessEngine {
    // No68-D-D17: the old code wrote into a shared singleton outBuffer
    // and returned it unconditionally. The native early-returns WITHOUT
    // writing when any input is non-finite or the screen is not positive
    // (native_tactical_engines.c: splendor_finite / screenW <= 0 paths),
    // so a rejected call shipped the PREVIOUS frame's compactness as
    // this frame's — and a NaN would flow straight into
    // PressingRecognitionEngine and TacticalAnalyticsEngine. A per-call
    // local buffer (No63-D24 precedent) makes a rejected call return
    // zeros instead of stale data, and the finite guard below keeps NaN
    // out of the result entirely.
    fun compute(scene: SceneSnapshot, defensiveLine: DefensiveLineResult, teamShape: TeamShapeResult): DefensiveCompactnessResult {
        // Phase 1 NativeReadinessGuard (part-1 pattern, AuthorityArbitrationEngine.kt:25):
        // a library-load failure must not fault VisionCore.process() every frame.
        // Return the zero result — the same fallback as the finite guard below —
        // and let gameplay continue on vision-only data.
        if (!com.assistant.NativeBridge.isNativeReady()) return DefensiveCompactnessResult()
        val outBuffer = FloatArray(4)
        com.assistant.NativeBridge.nativeDefensiveCompactness(scene.fieldConfidence, defensiveLine.confidence, teamShape.confidence, teamShape.width, teamShape.depth, teamShape.compactness, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outBuffer)
        if (!outBuffer[0].isFinite() || !outBuffer[1].isFinite() ||
            !outBuffer[2].isFinite() || !outBuffer[3].isFinite()) {
            return DefensiveCompactnessResult()
        }
        return DefensiveCompactnessResult(compactness = outBuffer[2], confidence = outBuffer[3])
    }
}

/* ========
DefensiveCompactnessResult
======== */
