object PossessionStyleRecognitionEngine {
    private val outBuffer = FloatArray(2)
    fun analyze(possession: BallPossessionResult, graph: PassingLaneGraph, pressure: PressureFieldResult): PossessionStyleRecognitionResult {
        com.assistant.NativeBridge.nativePossessionStyleCompute(possession.hasPossession, possession.possessionFrames, possession.confidence, graph.lanes.isNotEmpty(), pressure.rows, pressure.columns, outBuffer)
        return PossessionStyleRecognitionResult(outBuffer[0] > 0.5f, outBuffer[1])
    }
}
/* ======
PossessionStyleRecognitionEngine Anchor
====== */

/* ========
PossessionStyleRecognitionResult
======== */
