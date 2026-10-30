object WingOverloadDetectionEngine {
    private val outBuffer = FloatArray(4)
    fun compute(scene: SceneSnapshot, occupancy: SpaceOccupancyResult, pressure: PressureFieldResult): WingOverloadDetectionResult {
        com.assistant.NativeBridge.nativeWingOverloadCompute(scene.playerCount, scene.confidence, scene.fieldConfidence, pressure.rows, pressure.columns, occupancy.rows, occupancy.columns, outBuffer)
        return WingOverloadDetectionResult(outBuffer[0], outBuffer[1], outBuffer[2] > 0.5f, outBuffer[3])
    }
}
/* ======
WingOverloadDetectionEngine Anchor
====== */

/* ========
ZeroFramePressEngine
======== */
