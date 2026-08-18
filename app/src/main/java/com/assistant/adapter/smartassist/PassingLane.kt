package com.assistant.adapter.smartassist

data class PassingLane(
    val passer: TrackedPlayer,
    val receiver: TrackedPlayer,
    val distance: Float,
    val pressure: Float,
    val blocked: Boolean,
    val score: Float
)
