package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object PossessionStyleRecognitionEngine {
    private var heldSinceNanos: Long? = null
    fun analyze(possession: BallPossessionResult, graph: PassingLaneGraph, pressure: PressureFieldResult,
        nowNanos: Long = System.nanoTime()): PossessionStyleRecognitionResult {
        if (!possession.hasPossession) { heldSinceNanos = null; return PossessionStyleRecognitionResult() }
        val since = heldSinceNanos ?: nowNanos.also { heldSinceNanos = it }
        val measuredPressure = ObservedTactics.pressure(pressure) ?: return PossessionStyleRecognitionResult()
        val laneQuality = ObservedTactics.laneQuality(graph)
        val confidence = (possession.confidence.coerceIn(0f,1f) + laneQuality + (1f-measuredPressure)) / 3f
        return PossessionStyleRecognitionResult(nowNanos-since >= 1_500_000_000L && laneQuality > 0f, confidence)
    }
}

/* ========
PossessionStyleRecognitionResult
======== */
