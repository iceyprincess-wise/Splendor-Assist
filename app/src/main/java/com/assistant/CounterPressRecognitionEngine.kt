package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object CounterPressRecognitionEngine {
    private var previousPossession = false
    private var lastLossAt = Long.MIN_VALUE

    /**
     * Test hook (no53 round): resets the loss memory so unit tests start
     * from a known state. No production caller uses it. Tests call it in
     * @Before so JUnit's hash method order can never flip a result.
     */
    fun reset() {
        previousPossession = false
        lastLossAt = Long.MIN_VALUE
    }
    fun analyze(scene: SceneSnapshot, possession: BallPossessionResult, pressure: PressureFieldResult,
        nowNanos: Long = System.nanoTime()): CounterPressRecognitionResult {
        if (previousPossession && !possession.hasPossession && scene.ballVisible) lastLossAt = nowNanos
        previousPossession = possession.hasPossession
        val measuredPressure = ObservedTactics.pressure(pressure) ?: return CounterPressRecognitionResult()
        val lostRecently = lastLossAt != Long.MIN_VALUE && nowNanos - lastLossAt in 0L..800_000_000L
        if (!scene.ballVisible || possession.hasPossession || !lostRecently) return CounterPressRecognitionResult()
        return CounterPressRecognitionResult(measuredPressure > 0.4f,
            (scene.confidence.coerceIn(0f,1f) + measuredPressure) / 2f)
    }
}

/* ========
CounterPressRecognitionResult
======== */
