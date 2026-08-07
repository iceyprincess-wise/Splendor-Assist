package com.assistant.admin

/**
 * Live network readings published by the net engines so the admin panel's
 * Detector can recommend values from what THIS device is measuring RIGHT
 * NOW - no guessing. Plain @Volatile fields: lock-free to write from engine
 * threads, lock-free to read from the UI.
 */
object AdminLiveStats {

    @Volatile var rttMs = 0f; private set
    @Volatile var jitterMs = 0f; private set
    @Volatile var lossPct = -1f; private set          // -1 = not measured yet
    @Volatile var quality = "MEASURING"; private set
    @Volatile var transport = "UNKNOWN"; private set
    @Volatile var carrier = "UNKNOWN"; private set
    @Volatile var baselineRttMs = 0; private set
    @Volatile var jitterTolMs = 0; private set
    @Volatile var probeUpdatedMs = 0L; private set
    @Volatile var lossUpdatedMs = 0L; private set

    fun publishProbe(rtt: Float, jitter: Float, q: String, carrierName: String,
                     baseRtt: Int, jitTol: Int, trans: String) {
        rttMs = rtt; jitterMs = jitter; quality = q; carrier = carrierName
        baselineRttMs = baseRtt; jitterTolMs = jitTol; transport = trans
        probeUpdatedMs = System.currentTimeMillis()
    }

    fun publishLoss(pct: Float) {
        lossPct = pct
        lossUpdatedMs = System.currentTimeMillis()
    }

    /** True when probe numbers are fresh enough to trust (updated within 30s). */
    fun fresh(): Boolean =
        probeUpdatedMs > 0 && System.currentTimeMillis() - probeUpdatedMs < 30_000L
}
