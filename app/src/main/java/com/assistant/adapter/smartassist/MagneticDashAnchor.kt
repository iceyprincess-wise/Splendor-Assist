package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Omega Performance Parameters for High-Speed Synchronization
private const val ADHESION_COEFFICIENT = 1.25f       // Magnetic retention pull factor
private const val DRIFT_FILTER_BETA = 0.90f          // Jitter filter strength
private const val MIN_PULSE_INTERVAL_NS = 7_000_000L // ~142Hz micro-tick limit (7ms)
private const val OMEGA_TURNING_THRESHOLD = 6.0f     // Joystick displacement speed threshold

/*
 * Pure dash-anchor result. Added so the anchor concept can contribute a target
 * vector without owning input dispatch. The instance method
 * processHighSpeedDribble(...) is unchanged.
 */
data class DashAnchorResult(
    val anchorX: Float,
    val anchorY: Float,
    val dragVelocity: Float,
    val turning: Boolean,
    val strength: Float
)

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

        // 1. Fast Float Drag Velocity Calculation
        val deltaX = directionalX - lastDirectionalX
        val deltaY = directionalY - lastDirectionalY
        val dragVelocity = sqrt(deltaX * deltaX + deltaY * deltaY)

        // 2. Adaptive Pulse-Rate Interval Configuration
        val baseIntervalNs = if (dragVelocity > OMEGA_TURNING_THRESHOLD) {
            13_333_333L // 75Hz turn priority update (13.3ms)
        } else {
            25_000_000L // 40Hz linear lock update (25ms)
        }

        // Fast nano-scale timing scramble without object allocation
        val pacingJitterNs = ((currentTime and 0x0FL) - 8L) * 100_000L
        val dynamicIntervalNs = (baseIntervalNs + pacingJitterNs).coerceAtLeast(MIN_PULSE_INTERVAL_NS)

        if (elapsedNs > dynamicIntervalNs) {
            // 3. Jitter Suppression Low-Pass Filter
            val filteredX = (DRIFT_FILTER_BETA * directionalX) + ((1f - DRIFT_FILTER_BETA) * lastDirectionalX)
            val filteredY = (DRIFT_FILTER_BETA * directionalY) + ((1f - DRIFT_FILTER_BETA) * lastDirectionalY)

            // 4. Vector Geometry and Adhesion Extension
            val diffX = filteredX - dashX
            val diffY = filteredY - dashY
            val currentDistance = sqrt(diffX * diffX + diffY * diffY)

            val targetX: Float
            val targetY: Float

            if (currentDistance > 0.001f) {
                val angle = atan2(diffY, diffX)
                val optimizedDistance = currentDistance * ADHESION_COEFFICIENT
                targetX = dashX + cos(angle) * optimizedDistance
                targetY = dashY + sin(angle) * optimizedDistance
            } else {
                targetX = dashX
                targetY = dashY
            }

            // 5. Intelligent Gesture Duration Calculation
            val baseDurationMs = when {
                dragVelocity > 18.0f -> 10L   // Ultra-fast release
                currentDistance > 150f -> 50L // Deep continuous swipe
                else -> 30L                   // Standard responsive dribble
            }
            val adaptiveDurationMs = baseDurationMs.coerceAtLeast(8L)

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

                if (pulseCount % 100 == 0L) {
                    Log.d("MagneticDashAnchor", "Omega Stabilization active. Pulses injected: $pulseCount, Vel: $dragVelocity, Duration: ${adaptiveDurationMs}ms")
                }
            } catch (e: Exception) {
                Log.e("MagneticDashAnchor", "Zero-latency injection skipped: ${e.message}")
            }
        }
    }

    companion object {
        private const val PURE_DRIFT_BETA = 0.85f
        private const val PURE_TURN_THRESHOLD = 6.0f

        @Volatile
        private var prevDirectionalX = 0f

        @Volatile
        private var prevDirectionalY = 0f

        /*
         * Stateless anchor computation: drift-filtered directional target plus a
         * bounded strength. Synchronized with MagneticDashAnchor adhesion logic.
         */
        @JvmStatic
        fun computeAnchorTarget(
            dashX: Float,
            dashY: Float,
            directionalX: Float,
            directionalY: Float
        ): DashAnchorResult {
            val dx = directionalX - dashX
            val dy = directionalY - dashY
            val dist = sqrt(dx * dx + dy * dy)

            // Track directional drag velocity across consecutive calls
            val velX = directionalX - prevDirectionalX
            val velY = directionalY - prevDirectionalY
            val dragVelocity = sqrt(velX * velX + velY * velY)

            prevDirectionalX = directionalX
            prevDirectionalY = directionalY

            // Apply magnetic adhesion projection to contributor target
            val targetDist = if (dist > 0.001f) dist * ADHESION_COEFFICIENT else 0f
            val normX = if (dist > 0.001f) dx / dist else 1f
            val normY = if (dist > 0.001f) dy / dist else 0f

            val rawTargetX = dashX + normX * targetDist
            val rawTargetY = dashY + normY * targetDist

            val filteredX = (PURE_DRIFT_BETA * rawTargetX) + ((1f - PURE_DRIFT_BETA) * dashX)
            val filteredY = (PURE_DRIFT_BETA * rawTargetY) + ((1f - PURE_DRIFT_BETA) * dashY)

            val turning = dragVelocity > PURE_TURN_THRESHOLD || dist > 120f
            // Ensure non-zero authority under high pressure
            val strength = if (dist > 0f) (dist / 150f).coerceIn(0.35f, 1.0f) else 0.5f

            return DashAnchorResult(
                anchorX = filteredX.coerceAtLeast(0f),
                anchorY = filteredY.coerceAtLeast(0f),
                dragVelocity = dragVelocity,
                turning = turning,
                strength = strength
            )
        }
    }
}
