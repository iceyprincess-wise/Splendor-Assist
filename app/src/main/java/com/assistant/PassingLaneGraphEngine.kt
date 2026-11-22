package com.assistant

import kotlin.math.sqrt

object PassingLaneGraphEngine {

    fun build(
        scene: SceneSnapshot,
        pressure: PressureFieldResult
    ): PassingLaneGraph {

        val lanes = ArrayList<PassingLane>()

        val teammates =
            scene.trackedPlayers.filter {
                // No68-A-D7: a track with a non-finite coordinate poisons
                // the whole graph — distance goes NaN, score goes NaN,
                // and the NaN lane sorts first and wins the "best lane"
                // max in PreferredPassingLaneLearningEngine (its loop
                // deliberately matches maxOrNull's NaN-wins ordering).
                // FrameAssembler and ObservedTactics.players already
                // filter the same way; the graph must not trust coords
                // they would reject.
                it.isUserTeam && it.x.isFinite() && it.y.isFinite()
            }

        if (teammates.size < 2) {
            return PassingLaneGraph(emptyList())
        }

        val cols = pressure.columns
        val rows = pressure.rows
        val width = com.assistant.vision.CameraProfile.captureWidth.toFloat()
        val height = com.assistant.vision.CameraProfile.captureHeight.toFloat()
        if (width <= 0f || height <= 0f || ObservedTactics.pressure(pressure) == null) return PassingLaneGraph()

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
                    (((midX / width) * cols).toInt())
                        .coerceIn(0, cols - 1)

                val row =
                    (((midY / height) * rows).toInt())
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
