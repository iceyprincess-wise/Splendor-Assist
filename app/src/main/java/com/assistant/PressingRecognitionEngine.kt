package com.assistant

object PressingRecognitionEngine {
    // No68-I-D24: the old metric was INVERTED. It read the MEAN of the
    // normalised pressure grid, but normalisation divides out the intensity:
    // on the harness geometry (288x640, grid 8x6) a concentrated 11-man
    // press on the ball scores mean 0.394, a spread-out opponent shape
    // scores 0.406, and a single far opponent scores 0.025 — so the > 0.4
    // gate fired when opponents were SPREAD (not pressing) and missed a
    // real concentrated press. Measured by replicating the exact 1/d kernel
    // over 48 cells in the sandbox (see defects.md §1). NOTE (no68
    // reconstruction): the first tracer's comment quoted 0.051 / 0.833 /
    // 0.397 for these three cases; those numbers could not be reproduced
    // from any geometry tried and have been corrected here to the replicated
    // values. The inversion itself stands, but the margins are thin
    // (0.394 vs 0.406) — the ball-cell metric, not the size of the gap, is
    // what makes the fix principled.
    // The fix keeps the same 0.4 threshold and the same compactness gate,
    // but applies them to the pressure AT THE BALL (the cell the ball sits
    // in): a press means opponents close to the ball. When the ball is not
    // visible there is nothing to judge pressing against, so the result is
    // the default (not detected). Default args keep old callers compiling.
    fun analyze(
        pressure: PressureFieldResult,
        compactness: DefensiveCompactnessResult,
        formation: FormationResult,
        ballX: Float = 0f,
        ballY: Float = 0f,
        frameWidth: Float = 0f,
        frameHeight: Float = 0f,
        ballVisible: Boolean = false
    ): PressingRecognitionResult {
        if (!ballVisible) return PressingRecognitionResult()
        // Validity guard (same shape/range rules as ObservedTactics.pressure,
        // without computing the mean it throws away).
        val grid = pressure.pressure
        if (pressure.rows <= 0 || pressure.columns <= 0 || grid.size != pressure.rows ||
            grid.any { it.size != pressure.columns || it.any { v -> !v.isFinite() || v !in 0f..1f } }
        ) return PressingRecognitionResult()
        val measuredPressure = pressure.cellPressureAt(ballX, ballY, frameWidth, frameHeight)
        if (!formation.found || !compactness.compactness.isFinite()) return PressingRecognitionResult()
        val confidence = (formation.confidence.coerceIn(0f,1f) + compactness.confidence.coerceIn(0f,1f) + measuredPressure) / 3f
        return PressingRecognitionResult(measuredPressure > 0.4f && compactness.compactness > 0.55f, confidence)
    }
}
