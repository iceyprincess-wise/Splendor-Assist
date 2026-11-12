package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object CrossPrecisionEngine {
    private val outCross = FloatArray(3)

    fun calculate(x: Float, y: Float, strength: Int): CrossPrecisionResult {
        NativePorts.cross(x, y, strength, outCross)
        return CrossPrecisionResult(outCross[0], outCross[1], outCross[2])
    }

    fun stunningCrossLeadDistance(strikerVelocity: Float): Float = NativePorts.crossLead(strikerVelocity)
    fun stunningCrossSwipeDistance(): Float = NativePorts.crossSwipe()
    fun stunningCrossDuration(): Long = NativePorts.crossDuration()
}

/* ========
CrossingLaneAnalysisEngine
======== */
internal const val CROSSINGLANE_OMEGA_AUTHORITY_TAG = "CrossingLane.omega"

