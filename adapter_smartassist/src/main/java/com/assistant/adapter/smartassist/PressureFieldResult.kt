package com.assistant.adapter.smartassist

data class PressureFieldResult(
    val columns: Int,
    val rows: Int,
    val pressure: Array<FloatArray>
)
