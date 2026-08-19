package com.assistant.adapter.smartassist

object PassingLaneGraphEngine {

    fun build(
        scene: SceneSnapshot,
        pressure: PressureFieldResult
    ): PassingLaneGraph {

        val lanes = ArrayList<PassingLane>()

        val teammates =
            scene.trackedPlayers.filter {
                it.isUserTeam
            }

        if (teammates.size < 2) {
            return PassingLaneGraph(emptyList())
        }

        val cols =
            pressure.columns.coerceAtLeast(1)

        val rows =
            pressure.rows.coerceAtLeast(1)

        teammates.forEach { passer ->

            teammates.forEach receiverLoop@ { receiver ->

                if (passer.id == receiver.id) return@receiverLoop

                val dx = receiver.x - passer.x
                val dy = receiver.y - passer.y

                val distance =
                    kotlin.math.sqrt(dx * dx + dy * dy)

                val midX =
                    ((passer.x + receiver.x) * 0.5f)

                val midY =
                    ((passer.y + receiver.y) * 0.5f)

                val col =
                    (((midX / 1000f) * cols).toInt())
                        .coerceIn(0, cols - 1)

                val row =
                    (((midY / 1000f) * rows).toInt())
                        .coerceIn(0, rows - 1)

                val pressureValue =
                    pressure.pressure[row][col]

                val blocked =
                    pressureValue >= 0.70f

                val score =
                    (
                        (1f - pressureValue) *
                        (1f / (1f + distance / 500f))
                    ).coerceIn(0f, 1f)

                lanes += PassingLane(
                    passer = passer,
                    receiver = receiver,
                    distance = distance,
                    pressure = pressureValue,
                    blocked = blocked,
                    score = score
                )
            }
        }

        return PassingLaneGraph(
            lanes.sortedByDescending {
                it.score
            }
        )
    }
}
