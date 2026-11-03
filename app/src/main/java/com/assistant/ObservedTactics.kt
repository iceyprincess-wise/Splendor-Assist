package com.assistant

/** Spatial estimates from current pixel tracks. These are not measured game attributes. */
object ObservedTactics {
    fun players(scene: SceneSnapshot) = scene.trackedPlayers.filter {
        it.lastSeenFrame == scene.frameNumber && it.x.isFinite() && it.y.isFinite() &&
            it.confidence.isFinite() && it.confidence > 0f
    }
    fun balance(players: List<TrackedPlayer>): Float {
        if (players.isEmpty()) return 0f
        val ours = players.count { it.isUserTeam }
        return (2f * ours - players.size) / players.size
    }
    fun confidence(players: List<TrackedPlayer>): Float = if (players.isEmpty()) 0f
        else players.map { it.confidence.coerceIn(0f, 1f).toDouble() }.average().toFloat()
    fun pressure(field: PressureFieldResult): Float? {
        if (field.rows <= 0 || field.columns <= 0 || field.pressure.size != field.rows ||
            field.pressure.any { it.size != field.columns || it.any { v -> !v.isFinite() || v !in 0f..1f } }) return null
        var total = 0f
        for (row in field.pressure) for (value in row) total += value
        return total / (field.rows * field.columns)
    }
    fun laneQuality(graph: PassingLaneGraph): Float = graph.lanes.filter { !it.blocked && it.score.isFinite() }
        .maxOfOrNull { it.score.coerceIn(0f, 1f) } ?: 0f
}
