package com.assistant






























































































object PossessionStyleRecognitionEngine {
    private var heldSinceNanos: Long? = null

    /**
     * Test hook (no53 round): resets the hold timer so unit tests start
     * from a known state. No production caller uses it. Tests call it in
     * @Before so JUnit's hash method order can never flip a result.
     */
    fun reset() {
        heldSinceNanos = null
    }
    fun analyze(possession: BallPossessionResult, graph: PassingLaneGraph, pressure: PressureFieldResult,
        nowNanos: Long = System.nanoTime()): PossessionStyleRecognitionResult {
        if (!possession.hasPossession) { heldSinceNanos = null; return PossessionStyleRecognitionResult() }
        // No68-G-D1: validate the pressure grid BEFORE arming the hold
        // timer. The old order armed heldSinceNanos first, so hold time
        // accrued through malformed-pressure frames and detection could
        // fire on the first valid frame after 1.5 s of blind frames.
        val measuredPressure = ObservedTactics.pressure(pressure) ?: return PossessionStyleRecognitionResult()
        val since = heldSinceNanos ?: nowNanos.also { heldSinceNanos = it }
        val laneQuality = ObservedTactics.laneQuality(graph)
        val confidence = (possession.confidence.coerceIn(0f,1f) + laneQuality + (1f-measuredPressure)) / 3f
        return PossessionStyleRecognitionResult(nowNanos-since >= 1_500_000_000L && laneQuality > 0f, confidence)
    }
}

/* ========
PossessionStyleRecognitionResult
======== */
