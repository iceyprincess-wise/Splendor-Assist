package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class RankedReceiver(
    val player:TrackedPlayer,
    val score:Float
)

data class ReceiverRankingResult(
    val receivers:List<RankedReceiver> = emptyList()
)

object ReceiverRankingEngine{

    fun analyze(
        graph:PassingLaneGraph
    ):ReceiverRankingResult{

        val ranked=
            graph.lanes.map{

                val p=it.receiver

                val movement=
                    hypot(
                        p.velocityX.toDouble(),
                        p.velocityY.toDouble()
                    ).toFloat()/150f

                RankedReceiver(
                    player=p,
                    score=
                        (
                            it.score+
                            movement+
                            (1f-it.pressure)
                        ).coerceIn(0f,1f)
                )
            }.sortedByDescending{
                it.score
            }

        return ReceiverRankingResult(ranked)
    }
}
