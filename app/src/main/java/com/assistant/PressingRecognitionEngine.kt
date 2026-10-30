object PressingRecognitionEngine {
    private val outBuffer = FloatArray(2)
    fun analyze(pressure: PressureFieldResult, compactness: DefensiveCompactnessResult, formation: FormationResult): PressingRecognitionResult {
        com.assistant.NativeBridge.nativePressingRecognitionCompute(pressure.rows, pressure.columns, formation.found, formation.confidence, compactness.compactness, compactness.confidence, outBuffer)
        return PressingRecognitionResult(outBuffer[0] > 0.5f, outBuffer[1])
    }
}
/* ======
PressingRecognitionEngine Anchor
====== */

/* ========
ReceiverRankingEngine
======== */
