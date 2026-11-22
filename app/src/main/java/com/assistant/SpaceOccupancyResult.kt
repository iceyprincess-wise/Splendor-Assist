package com.assistant

data class SpaceOccupancyResult(
    val columns: Int,
    val rows: Int,
    val occupancy: Array<IntArray>
)
