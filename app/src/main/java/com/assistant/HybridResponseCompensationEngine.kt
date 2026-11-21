package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object HybridResponseCompensationEngine {

    fun compensate(
        startX:Float,
        startY:Float,
        endX:Float,
        endY:Float,
        strength:Int
    ): CompensationResult {

        val dx=endX-startX
        val dy=endY-startY

        val worldState=

            Phase3WorldStateStore.current()

        val temporal=
            worldState.temporalMemoryState

        val adaptiveConfidence=
            worldState.runtimeConfidenceCalibrationResult.calibratedConfidence

        val adaptationGain=
            worldState.onlineParameterAdaptationResult.adaptationGain

        val responseBoost =
            (
                adaptiveConfidence +
                adaptationGain +
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                (strength.coerceIn(0,100) / 100f)
            ) / 6f

        val predictiveFactor=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                adaptiveConfidence+
                adaptationGain+
                responseBoost
            )/6f

        val compensatedX =
            (endX + dx * predictiveFactor).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())

        val compensatedY =
            (endY + dy * predictiveFactor).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())

        // Item 13: duration/confidence/urgency DELETED — computed every
        // call and discarded by the only caller (it reads endX/endY
        // only). The duration that reaches dispatch is the raw input
        // through the boost/FrameDrop/tick-sync chain, never the
        // reduced duration. Behaviour unchanged.
        return CompensationResult(
            compensatedX,
            compensatedY
        )
    }
}

/* ========
InAppAgentCore
======== */
