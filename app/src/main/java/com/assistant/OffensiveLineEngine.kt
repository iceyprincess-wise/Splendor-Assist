object OffensiveLineEngine {

    fun compute(
        scene: SceneSnapshot
    ): OffensiveLineResult {

        val attackers =
            scene.trackedPlayers.filter { it.isUserTeam }

        if (attackers.isEmpty()) {
            return OffensiveLineResult(found = false)
        }

        val minX = attackers.minOf { it.x }
        val maxX = attackers.maxOf { it.x }
        // Float.average() accumulates in Double, then converts to Float.
        // Keep the same input order and arithmetic while avoiding two mapped lists.
        var sumX = 0.0
        var sumConfidence = 0.0
        for (i in attackers.indices) {
            val player = attackers[i]
            sumX += player.x.toDouble()
            sumConfidence += player.confidence.toDouble()
        }
        val averageX = (sumX / attackers.size).toFloat()
        val confidence = (sumConfidence / attackers.size).toFloat()

        return OffensiveLineResult(
            found = true,
            averageX = averageX,
            minX = minX,
            maxX = maxX,
            playerCount = attackers.size,
            confidence = confidence
        )
    }
}
/* ======
OffensiveLineEngine Anchor
====== */

/* ========
OffensiveLineResult
======== */
