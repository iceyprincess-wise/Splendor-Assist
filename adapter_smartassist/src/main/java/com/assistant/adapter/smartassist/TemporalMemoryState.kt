package com.assistant.adapter.smartassist

data class TemporalMemoryState(
    val historyWindow:Int=30,
    val sampleCount:Int=0,
    val rollingConfidence:Float=0f,
    val exponentialMovingAverage:Float=0f,
    val confidenceTrend:Float=0f,
    val confidenceVariance:Float=0f,
    val historyStability: Float = 0f,
    val confidenceSlope:Float=0f,
    val confidenceEvolution:Float=0f,
    val observationAge:Int=0,
    val decayFactor:Float=0.98f,
    val minConfidence:Float=0f,
    val maxConfidence:Float=0f,
    val temporalConfidence:Float=0f,
    val rollingMean:Float=0f,
    val rollingStdDev:Float=0f,
    val onlineUpdateCount:Int=0,
    val history:List<Float> = emptyList()
)
