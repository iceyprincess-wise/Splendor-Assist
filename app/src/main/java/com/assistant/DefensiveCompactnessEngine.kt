package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object DefensiveCompactnessEngine {
    private val outBuffer = FloatArray(4)
    fun compute(scene: SceneSnapshot, defensiveLine: DefensiveLineResult, teamShape: TeamShapeResult): DefensiveCompactnessResult {
        com.assistant.NativeBridge.nativeDefensiveCompactness(scene.fieldConfidence, defensiveLine.confidence, teamShape.confidence, teamShape.width, teamShape.depth, teamShape.compactness, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outBuffer)
        return DefensiveCompactnessResult(outBuffer[0], outBuffer[1], outBuffer[2], outBuffer[3])
    }
}

/* ========
DefensiveCompactnessResult
======== */
