package com.assistant



































































data class RehydratedStateSnapshot(
    val componentName: String,
    val lifecycleState: String,
    val timestamp: Long,
    val memoryPressure: String,
    val details: String
)

/* ========
RehydrationEngine
======== */
