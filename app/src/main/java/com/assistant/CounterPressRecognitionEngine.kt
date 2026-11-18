package com.assistant






























































































object CounterPressRecognitionEngine {
    private var previousPossession = false
    private var lastLossAt = Long.MIN_VALUE
    // No68-G-D11: pressure measured at the loss (or the first valid
    // pressure inside the window), not the trigger frame's pressure.
    private var lastLossPressure: Float? = null

    /**
     * Test hook (no53 round): resets the loss memory so unit tests start
     * from a known state. No production caller uses it. Tests call it in
     * @Before so JUnit's hash method order can never flip a result.
     */
    fun reset() {
        previousPossession = false
        lastLossAt = Long.MIN_VALUE
        lastLossPressure = null
    }
    fun analyze(scene: SceneSnapshot, possession: BallPossessionResult, pressure: PressureFieldResult,
        nowNanos: Long = System.nanoTime()): CounterPressRecognitionResult {
        // No68-G-D10: arm the window on ANY possession flip. The old code
        // required scene.ballVisible on the flip frame, so a loss that
        // happened while the ball was briefly unseen never started the
        // window. The trigger below still requires ballVisible.
        val lost = previousPossession && !possession.hasPossession
        previousPossession = possession.hasPossession
        if (lost) {
            lastLossAt = nowNanos
            lastLossPressure = null
        }
        val measuredPressure = ObservedTactics.pressure(pressure) ?: return CounterPressRecognitionResult()
        if (lost) lastLossPressure = measuredPressure
        // First valid pressure inside the window stands in for the loss
        // frame's pressure when that frame's grid was malformed.
        if (lastLossPressure == null && lastLossAt != Long.MIN_VALUE &&
            nowNanos - lastLossAt in 0L..800_000_000L) lastLossPressure = measuredPressure
        val lostRecently = lastLossAt != Long.MIN_VALUE && nowNanos - lastLossAt in 0L..800_000_000L
        if (!scene.ballVisible || possession.hasPossession || !lostRecently) return CounterPressRecognitionResult()
        return CounterPressRecognitionResult((lastLossPressure ?: 0f) > 0.4f,
            (scene.confidence.coerceIn(0f,1f) + measuredPressure) / 2f)
    }
}

/* ========
CounterPressRecognitionResult
======== */
