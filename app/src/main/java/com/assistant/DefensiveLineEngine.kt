package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object DefensiveLineEngine {

    /** compute() — OPPONENT back line (unchanged). */
    fun compute(scene: SceneSnapshot): DefensiveLineResult {
        var count = 0
        var sumX = 0.0
        var sumConfidence = 0.0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            if (player.isUserTeam) continue
            count++
            sumX += player.x.toDouble()
            sumConfidence += player.confidence.toDouble()
            if (player.x < minX) minX = player.x
            if (player.x > maxX) maxX = player.x
        }
        if (count == 0) return DefensiveLineResult(found=false)
        return DefensiveLineResult(true, (sumX / count).toFloat(), minX, maxX,
            count, (sumConfidence / count).toFloat())
    }

    /** computeUserLine() — OUR back line (NEW).
     *  Deepest 40% of user-team players by Y, capped at 5. */
    fun computeUserLine(scene: SceneSnapshot): DefensiveLineResult {
        val users = ArrayList<TrackedPlayer>(scene.trackedPlayers.size)
        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            if (player.isUserTeam) users.add(player)
        }
        if (users.isEmpty()) return DefensiveLineResult(found=false)
        // Preserve the original stable descending-Y order and take count exactly.
        users.sortByDescending { it.y }
        val keepCount = (users.size * 0.4f).toInt().coerceAtLeast(1).coerceAtMost(5)
        var sumX = 0.0
        var sumConfidence = 0.0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        for (i in 0 until keepCount) {
            val player = users[i]
            sumX += player.x.toDouble()
            sumConfidence += player.confidence.toDouble()
            if (player.x < minX) minX = player.x
            if (player.x > maxX) maxX = player.x
        }
        return DefensiveLineResult(true, (sumX / keepCount).toFloat(), minX, maxX,
            keepCount, (sumConfidence / keepCount).toFloat())
    }
}

/* ========
DefensiveLineResult
======== */
