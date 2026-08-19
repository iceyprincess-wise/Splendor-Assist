package com.assistant.adapter.smartassist

data class TemporalMemoryState(
    val historyWindow: Int = 30,
    val sampleCount: Int = 0,
    val rollingConfidence: Float = 0f,
    val exponentialMovingAverage: Float = 0f,
    val confidenceTrend: Float = 0f,
    val confidenceVariance: Float = 0f,
    val historyStability: Float = 0f,
    val confidenceSlope: Float = 0f,
    val confidenceEvolution: Float = 0f,
    val observationAge: Int = 0,
    val decayFactor: Float = 0.98f,
    val minConfidence: Float = 0f,
    val maxConfidence: Float = 0f,
    val temporalConfidence: Float = 0f,
    val rollingMean: Float = 0f,
    val rollingStdDev: Float = 0f,
    val onlineUpdateCount: Int = 0,
    
    // --- OMEGA UPGRADE: ZERO-ALLOCATION CIRCULAR BUFFER ---
    val history: FloatArray = FloatArray(historyWindow),
    val historyIndex: Int = 0,
    
    // --- OMEGA UPGRADE: ADAPTIVE KINEMATICS & SYNC ---
    val gestureScaleMultiplier: Float = 1.0f,
    val humanizedHoldDelayMs: Long = 0L,
    val frameSyncOffsetMs: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TemporalMemoryState

        if (historyWindow != other.historyWindow) return false
        if (sampleCount != other.sampleCount) return false
        if (historyIndex != other.historyIndex) return false
        if (!history.contentEquals(other.history)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = historyWindow
        result = 31 * result + sampleCount
        result = 31 * result + historyIndex
        result = 31 * result + history.contentHashCode()
        return result
    }
}
