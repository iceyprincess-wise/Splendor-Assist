package com.assistant.adapter.smartassist

import android.util.Log
import kotlin.math.pow

data class DefenseAuthorityResult(
    val containment: Float,
    val interception: Float,
    val pressure: Float
)

// FIX: removed +/-8% random noise — made identical situations produce different authority.
object DefenseAuthorityEngine {

    data class DefenseEvaluationDiagnostics(
        val totalEvaluations: Long,
        val maxContainmentObserved: Float,
        val maxInterceptionObserved: Float,
        val lastDistanceEvaluated: Float,
        val lastUpdatedTimestamp: Long
    )

    private var evaluationCount: Long = 0L
    private var peakContainment: Float = 0f
    private var peakInterception: Float = 0f
    private var lastDistance: Float = 0f
    private var lastUpdateMs: Long = 0L

    @Synchronized fun getEvaluationDiagnostics() = DefenseEvaluationDiagnostics(
        evaluationCount, peakContainment, peakInterception, lastDistance, lastUpdateMs)

    fun evaluate(distance:Float, strength:Int, recovery:Float, retention:Float): DefenseAuthorityResult {
        val ns = strength.coerceIn(0,100)/100f
        val ti = ns.pow(1.5f)
        val pf = 1f - (distance.coerceIn(0f,1200f)/1200f)
        val nr = recovery.coerceIn(0f,10f)/10f
        val nt = retention.coerceIn(0f,10f)/10f

        val containment  = ((nr*5.5f)+(ti*3.5f)+(pf*2.5f)).coerceIn(0f,10f)
        val interception = ((nt*5.5f)+(ti*3.5f)+(pf*2.5f)).coerceIn(0f,10f)
        val pressure     = (containment+interception).coerceIn(0f,20f)

        synchronized(this) {
            evaluationCount++
            if (containment  > peakContainment)  peakContainment  = containment
            if (interception > peakInterception) peakInterception = interception
            lastDistance = distance; lastUpdateMs = System.currentTimeMillis()
        }
        if (evaluationCount % 500L == 0L)
            Log.d("DefenseAuthorityEngine","containment=$containment interception=$interception pressure=$pressure")

        return DefenseAuthorityResult(containment, interception, pressure)
    }
}
