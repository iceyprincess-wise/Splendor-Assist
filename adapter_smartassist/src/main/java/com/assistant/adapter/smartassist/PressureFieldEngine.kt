package com.assistant.adapter.smartassist

import kotlin.math.sqrt

object PressureFieldEngine {

    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 6

    fun compute(
        scene: SceneSnapshot,
        frameWidth: Float,
        frameHeight: Float
    ): PressureFieldResult {

        val grid = Array(GRID_ROWS) { FloatArray(GRID_COLUMNS) }

        if (frameWidth <= 0f || frameHeight <= 0f) {
            return PressureFieldResult(GRID_COLUMNS, GRID_ROWS, grid)
        }

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLUMNS) {

                val x = (col + 0.5f) * frameWidth / GRID_COLUMNS
                val y = (row + 0.5f) * frameHeight / GRID_ROWS

                var pressure = 0f

                scene.trackedPlayers.forEach { player ->
                    val dx = player.x - x
                    val dy = player.y - y
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                    pressure += 1f / d
                }

                grid[row][col] = pressure
            }
        }

        var maxValue = 0f

        grid.forEach { r ->
            r.forEach {
                if (it > maxValue) maxValue = it
            }
        }

        if (maxValue > 0f) {
            grid.forEachIndexed { r, _ ->
                grid[r].indices.forEach { c ->
                    grid[r][c] /= maxValue
                }
            }
        }

        return PressureFieldResult(
            GRID_COLUMNS,
            GRID_ROWS,
            grid
        )
    }
}
