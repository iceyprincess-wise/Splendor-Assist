package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object ShotOpportunityAnalysisEngine {
    fun analyze(distance:Float, pressure:Float):ShotOpportunityResult {
        val outBuffer = FloatArray(3)
        NativePorts.shot(distance, pressure, outBuffer)
        return ShotOpportunityResult(outBuffer[0], outBuffer[1])
    }
}

/* ========
SmartAssistUltimateCorrectorEngine
======== */
