package com.assistant.adapter.smartassist

data class PredictedRun(
    val player:TrackedPlayer,
    val predictedX:Float,
    val predictedY:Float,
    val confidence:Float
)

data class RunPredictionResult(
    val runs:List<PredictedRun> = emptyList()
)

object RunPredictionEngine{

    fun analyze(
        scene:SceneSnapshot
    ):RunPredictionResult{

        val runs=
            scene.trackedPlayers
                .filter{it.isUserTeam}
                .map{

                    PredictedRun(
                        player=it,
                        predictedX=it.x+(it.velocityX*0.45f),
                        predictedY=it.y+(it.velocityY*0.45f),
                        confidence=it.confidence.coerceIn(0f,1f)
                    )

                }.sortedByDescending{
                    it.confidence
                }

        return RunPredictionResult(runs)
    }
}
