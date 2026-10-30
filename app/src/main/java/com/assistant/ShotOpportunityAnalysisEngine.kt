object ShotOpportunityAnalysisEngine {
    private val outBuffer = FloatArray(3)
    fun analyze(distance:Float, pressure:Float):ShotOpportunityResult {
        NativePorts.shot(distance, pressure, outBuffer)
        return ShotOpportunityResult(outBuffer[0], outBuffer[1], outBuffer[2])
    }
}
/* ======
ShotOpportunityAnalysisEngine Anchor
====== */

/* ========
SmartAssistUltimateCorrectorEngine
======== */
