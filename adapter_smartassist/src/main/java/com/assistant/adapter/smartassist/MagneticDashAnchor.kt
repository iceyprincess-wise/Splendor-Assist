package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// Omega Performance Parameters for High-Speed Synchronization
private const val ADHESION_COEFFICIENT = 1.25f       // Magnetic retention pull factor (increased for stronger effect)
private const val DRIFT_FILTER_BETA = 0.90f          // Jitter filter strength (prevents touch-snapping penalties) (increased for smoother effect)
private const val MIN_PULSE_INTERVAL_NS = 7_000_000L // ~142Hz micro-tick limit for extreme physical response (7ms) (increased for faster response)
private const val OMEGA_TURNING_THRESHOLD = 6.0f     // Joystick displacement speed to trigger instant turning update (increased for faster turning)

class MagneticDashAnchor(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    private var lastPulseTime = 0L
    private var lastDirectionalX = 0f
    private var lastDirectionalY = 0f
    private var pulseCount = 0L

    fun processHighSpeedDribble(
        dashX: Float,
        dashY: Float,
        directionalX: Float,
        directionalY: Float
    ) {
        val currentTime = System.nanoTime()
        val elapsedNs = currentTime - lastPulseTime

        // 1. Calculate input drag velocity to detect rapid turn requests
        val deltaX = directionalX - lastDirectionalX
        val deltaY = directionalY - lastDirectionalY
        val dragVelocity = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        // 2. Adaptive Pulse-Rate Interval Configuration with Anti-Telemetry Jitter
        // Tight turns require immediate updates to override physical inertia,
        // while straight runs benefit from a steady micro-pulse rate.
        val baseIntervalNs = if (dragVelocity > OMEGA_TURNING_THRESHOLD) {
            13_333_333L // 75Hz turn priority update (13.3ms) (increased for faster turning)
        } else {
            25_000_000L // 40Hz linear lock update (25ms) (increased for smoother effect)
        }

        // Add nano-scale timing scramble to disrupt rigid interval pattern logs
        val pacingJitterNs = Random.nextLong(-700_000L, 700_000L)
        val dynamicIntervalNs = (baseIntervalNs + pacingJitterNs).coerceAtLeast(MIN_PULSE_INTERVAL_NS)

        if (elapsedNs > dynamicIntervalNs) {
            // 3. Jitter Suppression Low-Pass Filter
            val filteredX = (DRIFT_FILTER_BETA * directionalX) + ((1f - DRIFT_FILTER_BETA) * lastDirectionalX)
            val filteredY = (DRIFT_FILTER_BETA * directionalY) + ((1f - DRIFT_FILTER_BETA) * lastDirectionalY)

            // 4. Vector Geometry and Adhesion Extension
            val angle = atan2((filteredY - dashY).toDouble(), (filteredX - dashX).toDouble())
            val currentDistance = hypot((filteredX - dashX).toDouble(), (filteredY - dashY).toDouble()).toFloat()

            // Align and magnetically project vectors based on custom pull factor
            val optimizedDistance = currentDistance * ADHESION_COEFFICIENT

            // Introduce a subtle, organic target shift to avoid machine-straight line logs
            val coordinateNoiseX = Random.nextFloat() * 1.5f - 0.75f // +/- 0.75 pixel variance (increased for more natural effect)
            val coordinateNoiseY = Random.nextFloat() * 1.5f - 0.75f

            val targetX = dashX + (cos(angle) * optimizedDistance).toFloat() + coordinateNoiseX
            val targetY = dashY + (sin(angle) * optimizedDistance).toFloat() + coordinateNoiseY

            // 5. Intelligent Gesture Duration Calculation (Humanized Window Mapping)
            // Sharp evasive turns utilize ultra-short touch windows to avoid engine friction,
            // while long strides scale up to sustain speed boosts.
            val baseDurationMs = when {
                dragVelocity > 18.0f -> 10L   // Ultra-fast release to maintain maximum turning frame rate (decreased for faster response)
                currentDistance > 150f -> 50L // Deep continuous swipe for sustained physical push (increased for more powerful effect)
                else -> 30L                  // Optimal responsive standard dribble touch width (increased for more natural effect)
            }

            // Fluctuates duration dynamically by +/- 1ms or 2ms to blend into normal human variances
            val durationVariance = Random.nextLong(-1, 3)
            val adaptiveDurationMs = (baseDurationMs + durationVariance).coerceAtLeast(8L)

            // 6. Zero-Latency Execution Ingress
            try {
                inputEngine.injectZeroLatencySwipe(
                    dashX,
                    dashY,
                    targetX,
                    targetY,
                    adaptiveDurationMs
                )

                pulseCount++
                lastPulseTime = currentTime
                lastDirectionalX = filteredX
                lastDirectionalY = filteredY

                // Throttle logs slightly to maintain performance on low-end devices
                if (pulseCount % 100 == 0L) {
                    Log.d("MagneticDashAnchor", "Omega Stabilization active. Pulses injected: $pulseCount, Vel: $dragVelocity, Duration: ${adaptiveDurationMs}ms")
                }
            } catch (e: Exception) {
                Log.e("MagneticDashAnchor", "Zero-latency injection skipped: ${e.message}")
            }
        }
    }
}
