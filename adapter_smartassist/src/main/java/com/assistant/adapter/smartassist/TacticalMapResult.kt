package com.assistant.adapter.smartassist

data class TacticalMapResult(
    val width: Int = 0,
    val height: Int = 0,
    val cells: FloatArray = FloatArray(0),
    val confidence: Float = 0f
)
