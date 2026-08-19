package com.assistant.adapter.smartassist

import kotlin.math.sqrt
import kotlin.random.Random

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

                // Introduce spatial coordinate fuzzing to break up rigid cell tracking borders
                val horizontalFuzz = Random.nextFloat() * 0.08f - 0.04f // Tiny cell offset bounds
                val verticalFuzz = Random.nextFloat() * 0.08f - 0.04f

                val x = (col + 0.5f + horizontalFuzz) * frameWidth / GRID_COLUMNS
                val y = (row + 0.5f + verticalFuzz) * frameHeight / GRID_ROWS

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
            // Add a minute float variance to disrupt absolute identical mathematical normalized limits
            val normalizationJitter = Random.nextFloat() * 0.002f - 0.001f
            val dynamicMax = maxValue + (maxValue * normalizationJitter)

            grid.forEachIndexed { r, _ ->
                grid[r].indices.forEach { c ->
                    if (dynamicMax > 0f) {
                        grid[r][c] /= dynamicMax
                    }
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
