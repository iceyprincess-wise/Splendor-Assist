object TacticalMapGenerationEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult,
        teamShape: TeamShapeResult,
        defensiveLine: DefensiveLineResult,
        offensiveLine: OffensiveLineResult
    ): TacticalMapResult {
        val columns = occupancy.columns
        val rows = occupancy.rows
        val cells = FloatArray(columns * rows)

        // Each cell blends the pressure field with the occupancy count and
        // the team-shape compactness, then is clamped to 0..1. Every input
        // parameter now contributes to the map instead of being ignored.
        val compactness = teamShape.compactness.coerceIn(0f, 1f)
        var maxCell = 0f
        var occupiedCells = 0
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val p = if (row < pressure.rows && col < pressure.columns)
                    pressure.pressure[row][col] else 0f
                val o = if (row < occupancy.rows && col < occupancy.columns)
                    occupancy.occupancy[row][col] else 0
                val occupancyWeight = (o / 4f).coerceIn(0f, 1f)
                val raw = p * 0.5f + occupancyWeight * 0.3f + compactness * 0.2f
                val cell = raw.coerceIn(0f, 1f)
                cells[row * columns + col] = cell
                if (cell > maxCell) maxCell = cell
                if (o > 0) occupiedCells++
            }
        }

        // Back four depth: how far behind the ball the defensive line sits.
        val defensiveDepth =
            if (defensiveLine.found) (scene.trackedBallX - defensiveLine.averageX) else 0f
        // How far ahead of the defensive line the attacking line pushes.
        val offensiveAdvance =
            if (offensiveLine.found) (offensiveLine.averageX - defensiveLine.averageX) else 0f

        return TacticalMapResult(
            width = columns,
            height = rows,
            cells = cells,
            confidence = scene.confidence.coerceIn(0f,1f),
            occupiedCells = occupiedCells,
            maxCellPressure = maxCell,
            defensiveDepth = defensiveDepth,
            offensiveAdvance = offensiveAdvance
        )
    }
}
/* ======
TacticalMapGenerationEngine Anchor
====== */

/* ========
TacticalMapResult
======== */
