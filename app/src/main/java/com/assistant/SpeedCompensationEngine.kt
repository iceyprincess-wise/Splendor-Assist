object SpeedCompensationEngine {
    private val outBuffer = FloatArray(5)
    fun compensate(distance: Float, angle: Float, strength: Int): SpeedCompensationResult {
        NativePorts.speed(distance, angle, strength, 0f, 0f, 0f, 0f, 0f, outBuffer)
        return SpeedCompensationResult(outBuffer[0], outBuffer[1], outBuffer[2], outBuffer[3], outBuffer[4])
    }
}
/* ======
SpeedCompensationEngine Anchor
====== */

/* ========
TacticalAnalyticsEngine
======== */
