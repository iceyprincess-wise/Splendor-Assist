package com.assistant.adapter.smartassist

data class SpaceOccupancyResult(
    val columns: Int,
    val rows: Int,
    val occupancy: Array<IntArray>
)
