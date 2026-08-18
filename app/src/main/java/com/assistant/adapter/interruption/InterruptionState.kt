package com.assistant.adapter.interruption

data class InterruptionState(
    val batteryLevel: Int,
    val charging: Boolean,
    val thermalStatus: Int,
    val severity: String,
    val timestamp: Long
)
