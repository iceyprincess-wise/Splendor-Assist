object RunPredictionEngine{

    fun analyze(
        scene:SceneSnapshot
    ):RunPredictionResult{

        val runs = ArrayList<PredictedRun>(scene.trackedPlayers.size)
        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            if (!player.isUserTeam) continue
            runs.add(PredictedRun(player, player.x + (player.velocityX * 0.45f),
                player.y + (player.velocityY * 0.45f), player.confidence.coerceIn(0f, 1f)))
        }
        runs.sortByDescending { it.confidence }
        return RunPredictionResult(runs)
    }
}
/* ======
RunPredictionEngine Anchor
====== */

/* ========
RuntimeConfidenceCalibrationEngine
======== */
