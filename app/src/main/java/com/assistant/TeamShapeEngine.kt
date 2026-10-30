object TeamShapeEngine {

    fun compute(
        scene: SceneSnapshot
    ): TeamShapeResult {

        if (scene.trackedPlayers.isEmpty()) {
            return TeamShapeResult(found = false)
        }

        val minX = scene.trackedPlayers.minOf { it.x }
        val maxX = scene.trackedPlayers.maxOf { it.x }

        val minY = scene.trackedPlayers.minOf { it.y }
        val maxY = scene.trackedPlayers.maxOf { it.y }

        val width = maxX - minX
        val depth = maxY - minY

        // Match Float.average(): ordered Double accumulation and one Float cast.
        // Indexed traversal avoids iterator and mapped-list allocations.
        var sumX = 0.0
        var sumY = 0.0
        var sumConfidence = 0.0
        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            sumX += player.x.toDouble()
            sumY += player.y.toDouble()
            sumConfidence += player.confidence.toDouble()
        }
        val centerX = (sumX / scene.trackedPlayers.size).toFloat()
        val centerY = (sumY / scene.trackedPlayers.size).toFloat()

        val compactness =
            sqrt(width * width + depth * depth)

        val confidence = (sumConfidence / scene.trackedPlayers.size).toFloat()

        return TeamShapeResult(
            found = true,
            width = width,
            depth = depth,
            centerX = centerX,
            centerY = centerY,
            compactness = compactness,
            confidence = confidence
        )
    }
}
/* ======
TeamShapeEngine Anchor
====== */

/* ========
TeamShapeResult
======== */
