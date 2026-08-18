package com.assistant.adapter.smartassist

data class ActiveAttackerResult(
    val found: Boolean,
    val attacker: TrackedPlayer? = null,
    val attackerIndex: Int = -1,
    val confidence: Float = 0f
)
