package com.assistant

import com.assistant.runtime.*

/**
 * Midfield compactness engine (queue item 5, batch midfield).
 *
 * Mirror of DefensiveCompactnessEngine, fed with MIDFIELD inputs
 * through the SAME native entry (NativeBridge.nativeDefensiveCompactness):
 * the midfield-line confidence (from MidfieldLineEngine) + a TeamShape
 * computed from the band players ONLY, using TeamShapeEngine's own
 * formulas (width = maxX-minX, depth = maxY-minY, compactness =
 * sqrt(width^2+depth^2), confidence = mean player confidence).
 *
 * Same scale as the defensive version (the native entry is shared):
 * LARGER = MORE SPREAD OUT — a tight midfield scores near 0, a
 * stretched one near 1. Same guards as the defensive version
 * (No68-D-D17 precedent): a per-call local buffer so a rejected
 * native call returns zeros instead of the previous frame's values,
 * and a finite guard that keeps NaN out of the result entirely.
 * A line that was not found, or a band shape that was not found,
 * returns the zero result WITHOUT calling native at all — a
 * single-player band would otherwise read as perfectly compact (0
 * width, 0 depth) and a missing line as a real measurement.
 *
 * Called from VisionCore.process() next to the defensive compactness
 * call, same cadence (every frame, unconditional). Deliberately NOT
 * wired into the pressing recogniser or the tactical analytics
 * engine — that would change pressing behaviour; out of scope.
 */
object MidfieldCompactnessEngine {

    fun compute(
        scene: SceneSnapshot,
        midfieldLine: MidfieldLineResult,
        teamShape: TeamShapeResult
    ): MidfieldCompactnessResult {
        if (!midfieldLine.found || !teamShape.found) return MidfieldCompactnessResult()
        // Phase 1 NativeReadinessGuard (part-1 pattern, AuthorityArbitrationEngine.kt:25):
        // a library-load failure must not fault VisionCore.process() every frame.
        // Return the zero result — the same fallback as the not-found line above.
        if (!com.assistant.NativeBridge.isNativeReady()) return MidfieldCompactnessResult()
        val outBuffer = FloatArray(4)
        com.assistant.NativeBridge.nativeDefensiveCompactness(
            scene.fieldConfidence, midfieldLine.confidence, teamShape.confidence,
            teamShape.width, teamShape.depth, teamShape.compactness,
            com.assistant.vision.CameraProfile.captureWidthOrFallback(),
            com.assistant.vision.CameraProfile.captureHeightOrFallback(),
            outBuffer
        )
        return fromBuffer(outBuffer)
    }

    /**
     * Pure buffer mapping, split out so the harness can pin it without
     * the native library: slot [2] is the diagonal compactness, slot
     * [3] the mean confidence. Any non-finite slot returns the zero
     * result (same guard the defensive engine applies inline).
     */
    fun fromBuffer(outBuffer: FloatArray): MidfieldCompactnessResult {
        if (!outBuffer[0].isFinite() || !outBuffer[1].isFinite() ||
            !outBuffer[2].isFinite() || !outBuffer[3].isFinite()
        ) {
            return MidfieldCompactnessResult()
        }
        return MidfieldCompactnessResult(outBuffer[2], outBuffer[3])
    }
}
