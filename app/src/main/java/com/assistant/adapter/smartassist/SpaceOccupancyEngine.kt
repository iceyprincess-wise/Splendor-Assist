package com.assistant.adapter.smartassist

object SpaceOccupancyEngine {

    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 6

    fun compute(
        scene: SceneSnapshot,
        frameWidth: Float,
        frameHeight: Float
    ): SpaceOccupancyResult {

        val grid = Array(GRID_ROWS) { IntArray(GRID_COLUMNS) }

        if (frameWidth <= 0f || frameHeight <= 0f) {
            return SpaceOccupancyResult(
                GRID_COLUMNS,
                GRID_ROWS,
                grid
            )
        }

        scene.trackedPlayers.forEach { player ->

            val col = ((player.x / frameWidth) * GRID_COLUMNS)
                .toInt()
                .coerceIn(0, GRID_COLUMNS - 1)

            val row = ((player.y / frameHeight) * GRID_ROWS)
                .toInt()
                .coerceIn(0, GRID_ROWS - 1)

            grid[row][col]++
        }

        return SpaceOccupancyResult(
            GRID_COLUMNS,
            GRID_ROWS,
            grid
        )
    }
}
