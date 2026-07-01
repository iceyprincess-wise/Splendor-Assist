package com.assistant.adapter.smartassist

data class ActiveDefenderResult(
    val found: Boolean,
    val defender: TrackedPlayer? = null,
    val defenderIndex: Int = -1,
    val distanceToAttacker: Float = Float.MAX_VALUE,
    val confidence: Float = 0f
)
