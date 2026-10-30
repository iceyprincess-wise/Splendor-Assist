object ReceiverRankingEngine{

    fun analyze(
        graph:PassingLaneGraph
    ):ReceiverRankingResult{

        val ranked=ArrayList<RankedReceiver>(graph.lanes.size)
        for(lane in graph.lanes){

            val p=lane.receiver

            val movement=
                hypot(
                    p.velocityX.toDouble(),
                    p.velocityY.toDouble()
                ).toFloat()/150f

            ranked.add(
                RankedReceiver(
                    player=p,
                    score=
                        (
                            lane.score+
                            movement+
                            (1f-lane.pressure)
                        ).coerceIn(0f,1f)
                )
            )
        }
        ranked.sortByDescending{
            it.score
        }

        return ReceiverRankingResult(ranked)
    }
}
/* ======
ReceiverRankingEngine Anchor
====== */

/* ========
RunPredictionEngine
======== */
