package com.assistant

import com.assistant.runtime.*

/**
 * Midfield line engine (queue item 5, batch midfield).
 *
 * The MEASURED midfield line: the middle band of user-team players.
 * Mirror of DefensiveLineEngine.computeUserLine, over the band between
 * two ALREADY-MEASURED values only — no new numeric constants, no
 * invented fudge factor:
 *   - Back edge = the user line's far edge toward the ball: the
 *     shallowest member of the deepest-40% back-line subset
 *     (userLine.maxX when attacking right, userLine.minX when
 *     attacking left — the direction DefensiveLineEngine sorts by).
 *   - Front edge = the offensive line's averageX (the mean of ALL
 *     user players, OffensiveLineEngine).
 *   - Band players = user-team players whose X lies strictly beyond
 *     the back edge and up to the front edge. The strict inequality on
 *     the back-edge side is what excludes the back-line subset itself:
 *     its members sit at or behind the back edge by construction.
 *   - If the front edge is behind the back edge (direction unconfident
 *     or the team shape inverts, e.g. the mean pulled deep by a
 *     cluster), the band is empty by the inequalities alone — no
 *     special case, no fabricated line: found=false.
 *   - Players ahead of the front edge are the front line, not the
 *     midfield, and are excluded by the front-edge bound.
 *
 * Statistics over band players only (mean of x + minX/maxX + mean
 * confidence), same arithmetic as computeUserLine. Direction comes
 * from AttackAxisModel.directionIfConfident(), the same call
 * computeUserLine uses, so the two lines can never disagree about
 * which way is deep.
 *
 * Pure Kotlin (no Android): pinned in the batch-midfield harness
 * (group 37).
 */
object MidfieldLineEngine {

    /**
     * The band players themselves, for callers that need the same set
     * (VisionCore feeds them to TeamShapeEngine for the midfield
     * compactness input). Pure: same result as compute() works over.
     */
    fun bandPlayers(
        scene: SceneSnapshot,
        userLine: DefensiveLineResult,
        offensiveLine: OffensiveLineResult
    ): List<TrackedPlayer> {
        if (!userLine.found || !offensiveLine.found) return emptyList()
        val direction = AttackAxisModel.directionIfConfident()
        val backEdge = if (direction < 0) userLine.minX else userLine.maxX
        val frontEdge = offensiveLine.averageX
        val band = ArrayList<TrackedPlayer>(scene.trackedPlayers.size)
        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            if (!player.isUserTeam) continue
            val x = player.x
            // Attacking left the front edge is the SMALLER x: the band
            // runs [frontEdge, backEdge). Attacking right (and on an
            // unconfident direction, the historical left-edge default)
            // it runs (backEdge, frontEdge]. Either way a front edge
            // behind the back edge admits nobody.
            val inside = if (direction < 0) x >= frontEdge && x < backEdge
                else x > backEdge && x <= frontEdge
            if (inside) band.add(player)
        }
        return band
    }

    fun compute(
        scene: SceneSnapshot,
        userLine: DefensiveLineResult,
        offensiveLine: OffensiveLineResult
    ): MidfieldLineResult {
        val band = bandPlayers(scene, userLine, offensiveLine)
        if (band.isEmpty()) return MidfieldLineResult(found = false)
        var sumX = 0.0
        var sumConfidence = 0.0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        for (i in band.indices) {
            val player = band[i]
            sumX += player.x.toDouble()
            sumConfidence += player.confidence.toDouble()
            if (player.x < minX) minX = player.x
            if (player.x > maxX) maxX = player.x
        }
        val averageX = (sumX / band.size).toFloat()
        val confidence = (sumConfidence / band.size).toFloat()
        if (!averageX.isFinite() || !minX.isFinite() ||
            !maxX.isFinite() || !confidence.isFinite()
        ) return MidfieldLineResult(found = false)
        return MidfieldLineResult(true, averageX, minX, maxX, confidence)
    }
}
