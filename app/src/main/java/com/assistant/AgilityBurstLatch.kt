package com.assistant

// No58 V4: hysteresis latch for the shield -> burst transition.
//
// The native engine reports burstIntensity every frame (0..1); the
// contributor returns one MOVE contribution per frame, so the shield ->
// burst exit is a temporal transition, not a single-frame decision. A
// bare "> 0.75" gate would flip the gesture direction on one noisy frame
// and flip it back on the next. The latch turns on above BURST_ON and
// stays on until the intensity falls below BURST_OFF, so the exit holds
// while the threat is real and releases only when it has genuinely eased.
//
// Pure Kotlin, no Android / NativeBridge dependency, so it is directly
// unit-testable. Single-threaded use only: AgilityContributor calls it
// from contribute(), which runs inside RuntimeDecisionLoop.onFrame()
// (@Synchronized, single caller) — a plain var is correct there. Do not
// share one latch across threads.
class AgilityBurstLatch {
    companion object {
        // On above 0.75 (the V4 burst-exit threshold), off below 0.5.
        // The 0.25 gap is the hysteresis band: values inside it hold the
        // previous state instead of chattering.
        const val BURST_ON = 0.75f
        const val BURST_OFF = 0.5f
    }

    // State before the most recent update() call. The contributor reads
    // this after update() to log the burst trigger exactly once, on the
    // frame the latch turns on (transition, not per-frame).
    var wasLatchedBeforeUpdate: Boolean = false
        private set

    var isLatched: Boolean = false
        private set

    // NaN leaves the state unchanged: a non-finite intensity is missing
    // telemetry, not evidence the burst ended (and not evidence it began).
    fun update(burstIntensity: Float): Boolean {
        wasLatchedBeforeUpdate = isLatched
        if (burstIntensity.isNaN()) return isLatched
        if (!isLatched && burstIntensity > BURST_ON) isLatched = true
        else if (isLatched && burstIntensity < BURST_OFF) isLatched = false
        return isLatched
    }

    fun reset() {
        wasLatchedBeforeUpdate = false
        isLatched = false
    }
}
