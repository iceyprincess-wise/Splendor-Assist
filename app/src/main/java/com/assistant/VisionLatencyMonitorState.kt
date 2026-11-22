package com.assistant

data class VisionLatencyMonitorState(
    val latencyMs:Float = 0f,
    // No68-E2-D19: latencyMs == 0f used to be ambiguous — "0 ms latency"
    // or "never measured". hasMeasurement is false until update() has
    // received a real reading; lastUpdatedMs (0 = never) lets readers
    // detect a stale measurement.
    val hasMeasurement: Boolean = false,
    val lastUpdatedMs: Long = 0L
)
