object OverlapDetectionEngine{

    fun analyze(
        runs:RunPredictionResult
    ):OverlapDetectionResult{

        val result = ArrayList<OverlapRun>(runs.runs.size)
        for (i in runs.runs.indices) {
            val run = runs.runs[i]
            val speed = hypot(run.player.velocityX.toDouble(), run.player.velocityY.toDouble()).toFloat()
            val confidence = ((speed / 120f) + run.confidence).coerceIn(0f, 1f)
            result.add(OverlapRun(run.player, run.predictedX, run.predictedY, confidence, confidence >= 0.50f))
        }
        result.sortByDescending { it.confidence }
        return OverlapDetectionResult(result)
    }
}
/* ======
OverlapDetectionEngine Anchor
====== */

/* ========
OverloadPlaystyleEngine
======== */
/*
 * OVERLOAD PLAYSTYLE ENGINE
 *
 * Tactical model: create or exploit numerical superiority in one zone.
 *   - Own overload  -> attack through the loaded zone.
 *   - Opponent overload -> switch play to the isolated far side.
 *
 * Pure calculation. Takes primitives only, returns a bounded result.
 * It never reads stores and never calls another engine, so it can be
 * rewritten at any scale without touching the rest of the runtime.
 */

