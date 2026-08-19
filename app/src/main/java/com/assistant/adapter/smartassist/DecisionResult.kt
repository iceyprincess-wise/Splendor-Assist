package com.assistant.adapter.smartassist

data class DecisionResult(
    val mode: Int,
    val strength: Int,
    val confidence: Float,
    val priority: Int,
    // OMEGA UPGRADE: Server-Tick & Frame-Rate Injection Vectors (Defaulted to avoid breaking existing code)
    val tickAlignedHoldMs: Long = 33L,
    val frameAlignedOffsetMs: Long = 0L,
    val humanizedNoiseX: Float = 0f,
    val humanizedNoiseY: Float = 0f,
    val vectorScaleAmplification: Float = 1f
)
