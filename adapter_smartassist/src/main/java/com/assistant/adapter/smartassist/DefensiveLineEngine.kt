package com.assistant.adapter.smartassist

object DefensiveLineEngine {

    /** compute() — OPPONENT back line (unchanged). */
    fun compute(scene: SceneSnapshot): DefensiveLineResult {
        val d = scene.trackedPlayers.filter { !it.isUserTeam }
        if (d.isEmpty()) return DefensiveLineResult(found=false)
        return DefensiveLineResult(true,
            d.map{it.x}.average().toFloat(), d.minOf{it.x}, d.maxOf{it.x},
            d.size, d.map{it.confidence}.average().toFloat())
    }

    /** computeUserLine() — OUR back line (NEW).
     *  Deepest 40% of user-team players by Y, capped at 5. */
    fun computeUserLine(scene: SceneSnapshot): DefensiveLineResult {
        val u = scene.trackedPlayers.filter { it.isUserTeam }
        if (u.isEmpty()) return DefensiveLineResult(found=false)
        val keep = u.sortedByDescending{it.y}
            .take((u.size*0.4f).toInt().coerceAtLeast(1).coerceAtMost(5))
        return DefensiveLineResult(true,
            keep.map{it.x}.average().toFloat(), keep.minOf{it.x}, keep.maxOf{it.x},
            keep.size, keep.map{it.confidence}.average().toFloat())
    }
}
