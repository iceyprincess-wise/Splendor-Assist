package com.assistant.adapter.smartassist

import android.util.Log
import kotlin.math.pow
import kotlin.random.Random

data class DefenseAuthorityResult(
    val containment: Float,
    val interception: Float,
    val pressure: Float
)

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

    @Synchronized
    fun getEvaluationDiagnostics(): DefenseEvaluationDiagnostics =
        DefenseEvaluationDiagnostics(
            totalEvaluations = evaluationCount,
            maxContainmentObserved = peakContainment,
            maxInterceptionObserved = peakInterception,
            lastDistanceEvaluated = lastDistance,
            lastUpdatedTimestamp = lastUpdateMs
        )

    fun evaluate(
        distance: Float,
        strength: Int,
        recovery: Float,
        retention: Float
    ): DefenseAuthorityResult {
        val normalizedStrength =
            strength.coerceIn(0, 100) / 100f

        val tacticalIntensity =
            normalizedStrength.pow(1.5f)

        val distanceLimit = 1200f
        val proximityFactor =
            1f - (distance.coerceIn(0f, distanceLimit) / distanceLimit)

        val normalizedRecovery =
            recovery.coerceIn(0f, 10f) / 10f

        val normalizedRetention =
            retention.coerceIn(0f, 10f) / 10f

        // Continuous mathematical curve noise injection to scramble predictable telemetry profiles
        val containmentNoise = Random.nextFloat() * 0.16f - 0.08f // Subtle fractional variance
        val interceptionNoise = Random.nextFloat() * 0.16f - 0.08f

        val containment =
            (
                (normalizedRecovery * 5.5f) +
                    (tacticalIntensity * 3.5f) +
                    (proximityFactor * 2.5f) + containmentNoise
                ).coerceIn(0f, 10f)

        val interception =
            (
                (normalizedRetention * 5.5f) +
                    (tacticalIntensity * 3.5f) +
                    (proximityFactor * 2.5f) + interceptionNoise
                ).coerceIn(0f, 10f)

        val pressure =
            (containment + interception)
                .coerceIn(0f, 20f)

        synchronized(this) {
            evaluationCount += 1L

            if (containment > peakContainment) {
                peakContainment = containment
            }

            if (interception > peakInterception) {
                peakInterception = interception
            }

            lastDistance = distance
            lastUpdateMs = System.currentTimeMillis()
        }

        // Dynamically shift log evaluation limits to break up structural cadence logs
        val loggingInterval = 500L + Random.nextLong(-15, 16)
        if (evaluationCount % loggingInterval == 0L) {
            Log.d(
                "DefenseAuthorityEngine",
                "Defense authority containment=$containment interception=$interception pressure=$pressure [Dynamic Interval]"
            )
        }

        return DefenseAuthorityResult(
            containment = containment,
            interception = interception,
            pressure = pressure
        )
    }
}
