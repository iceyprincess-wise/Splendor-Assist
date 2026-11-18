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

    /** computeUserLine() — OUR back line.
     *  Deepest 40% of user-team players by X-depth, capped at 5.
     *
     *  No68-D-D15: the old code sorted by Y ("deepest by Y"), but depth
     *  in this codebase is X — the goal sits at an X end of the frame
     *  (ThreatPriority zoneOf, F38-D4 live session: 8,018 pairs, goalCX
     *  at the X ends; TacticalMap defensiveDepth is ballX minus line
     *  averageX; AttackAxisModel derives direction from goalX minus
     *  userX). Sorting by Y picked the players nearest the BOTTOM of
     *  the screen — the touchline, not our goal — so the "back line"
     *  was the wrong set of players. Deepest now means closest to OUR
     *  goal on the X axis: attack right (direction > 0) puts our goal
     *  at the left edge (smallest X deepest); attack left puts it at
     *  the right edge (largest X deepest); unconfident direction (0)
     *  keeps the historical left-edge default, matching
     *  DefenseAuthorityContributor. */
    fun computeUserLine(scene: SceneSnapshot): DefensiveLineResult {
        val users = ArrayList<TrackedPlayer>(scene.trackedPlayers.size)
        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            if (player.isUserTeam) users.add(player)
        }
        if (users.isEmpty()) return DefensiveLineResult(found=false)
        // Stable order, deepest (closest to our goal) first.
        if (AttackAxisModel.directionIfConfident() < 0) {
            users.sortByDescending { it.x }
        } else {
            users.sortBy { it.x }
        }
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
