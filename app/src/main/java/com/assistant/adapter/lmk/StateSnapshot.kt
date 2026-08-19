package com.assistant.adapter.lmk

data class StateSnapshot(
    val componentName: String,
    val lifecycleState: String,
    val timestamp: Long,
    val memoryPressure: String,
    val details: String
)
