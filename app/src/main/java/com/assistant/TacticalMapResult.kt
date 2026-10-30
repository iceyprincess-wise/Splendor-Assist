data class TacticalMapResult(
    val width: Int = 0,
    val height: Int = 0,
    val cells: FloatArray = FloatArray(0),
    val confidence: Float = 0f,
    val occupiedCells: Int = 0,
    val maxCellPressure: Float = 0f,
    val defensiveDepth: Float = 0f,
    val offensiveAdvance: Float = 0f
)
/* ======
TacticalMapResult Anchor
====== */

/* ========
ThroughBallLaneAnalysisEngine
======== */
