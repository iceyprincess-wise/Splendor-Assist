package com.assistant.adapter.smartassist

object FormationEngine {


fun estimate(
    scene: SceneSnapshot
): FormationResult {

    val players =
        scene.trackedPlayers

    if (players.size < 7) {
        return FormationResult(
            found = false,
            name = "Unknown",
            confidence = 0f
        )
    }

    val user =
        players.filter { it.isUserTeam }

    if (user.size < 7) {
        return FormationResult(
            found = false,
            name = "Unknown",
            confidence = 0f
        )
    }

    val sorted =
        user.sortedBy { it.y }

    val minY = sorted.first().y
    val maxY = sorted.last().y

    val depth =
        (maxY - minY).coerceAtLeast(1f)

    val bands = IntArray(4)

    sorted.forEach {

        val band =
            (((it.y - minY) / depth) * 4f)
                .toInt()
                .coerceIn(0,3)

        bands[band]++
    }

    val templates = linkedMapOf(
        "4-3-3" to intArrayOf(1,4,3,3),
        "4-4-2" to intArrayOf(1,4,4,2),
        "3-5-2" to intArrayOf(1,3,5,2),
        "5-3-2" to intArrayOf(1,5,3,2),
        "5-4-1" to intArrayOf(1,5,4,1),
        "3-4-3" to intArrayOf(1,3,4,3)
    )

    var best = "UNKNOWN"
    var bestScore = Int.MAX_VALUE

    templates.forEach { (name, ref) ->

        var score = 0

        for (i in 0 until 4)
            score += kotlin.math.abs(ref[i] - bands[i])

        if (score < bestScore) {
            bestScore = score
            best = name
        }
    }

    val confidence =
        (1f - (bestScore / 12f))
            .coerceIn(0f,1f)

    return FormationResult(
        found = true,
        name = best,
        confidence = confidence
    )
}
}
