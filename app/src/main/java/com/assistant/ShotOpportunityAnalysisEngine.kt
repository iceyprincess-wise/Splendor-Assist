package com.assistant

object ShotOpportunityAnalysisEngine {
    fun analyze(distance:Float, pressure:Float):ShotOpportunityResult {
        // No68-F-D6: a NaN input used to flow through NativePorts.shot
        // into a NaN confidence/openSideScore. Answer "no opportunity"
        // instead of propagating NaN to the contributor.
        if (!distance.isFinite() || !pressure.isFinite()) return ShotOpportunityResult(0f, 0f)
        val outBuffer = FloatArray(3)
        NativePorts.shot(distance, pressure, outBuffer)
        return ShotOpportunityResult(outBuffer[0], outBuffer[1])
    }
}
