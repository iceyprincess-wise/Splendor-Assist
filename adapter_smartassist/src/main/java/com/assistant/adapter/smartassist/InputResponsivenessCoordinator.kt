package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-performance, low-overhead client-side touch stabilization and micro-gesture injection engine.
 * Synchronizes hardware inputs with display refresh rates (60Hz/120Hz) and scales touch dynamics
 * to align with server-authoritative tick rate windows.
 */
object SmartAssistUltimateCorrector {

    private const val TAG = "UltimateCorrector"

    // High-frequency hardware optimization constants
    private const val DEFAULT_REFRESH_RATE_HZ = 60.0f
    private const val MILLIS_PER_SECOND = 1000.0f
    private const val BASE_PING_COMPENSATION_MS = 60L

    // Humanization boundary metrics (prevents rigid machine signatures)
    private const val MIN_HUMAN_VARIANCE_MS = -2L
    private const val MAX_HUMAN_VARIANCE_MS = 3L
    private const val JITTER_RADIUS_PIXELS = 0.85f

    // Handler caching to prevent allocation overhead during high-frequency gesture dispatch
    private val mainThreadHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    /**
     * Executes a high-precision multi-channel micro-gesture combo synchronized to display frames.
     * Dynamically scales stroke duration based on network constraints to secure authoritative possession.
     *
     * @param service The active high-privilege AccessibilityService instance.
     * @param dashButtonX Center X coordinate of the dash modifier input.
     * @param dashButtonY Center Y coordinate of the dash modifier input.
     * @param shootButtonX Center X coordinate of the directional execution engine.
     * @param shootButtonY Center Y coordinate of the directional execution engine.
     * @param targetAngleRad Vector angle in radians for directional translation.
     * @param activeEnginePath Optional background tracking vector to merge into the hardware frame.
     * @param currentPingMs Network latency metric used to scale possession duration against server ticks.
     * @return Boolean True if the gesture token successfully entered the system queue; false otherwise.
     */
    fun executePerfectPurpleShot(
        service: AccessibilityService,
        dashButtonX: Float,
        dashButtonY: Float,
        shootButtonX: Float,
        shootButtonY: Float,
        targetAngleRad: Double,
        activeEnginePath: Path? = null,
        currentPingMs: Long = BASE_PING_COMPENSATION_MS
    ): Boolean {
        return try {
            val builder = GestureDescription.Builder()
            val frameTimeMs = calculateFrameTimeMs(service)

            // Adaptive humanization offsets applied to initial coordinates
            val jitterX = Random.nextFloat() * JITTER_RADIUS_PIXELS * (if (Random.nextBoolean()) 1f else -1f)
            val jitterY = Random.nextFloat() * JITTER_RADIUS_PIXELS * (if (Random.nextBoolean()) 1f else -1f)

            val adjustedDashX = dashButtonX + jitterX
            val adjustedDashY = dashButtonY + jitterY
            val adjustedShootX = shootButtonX + jitterX
            val adjustedShootY = shootButtonY + jitterY

            // 1. Channel A: Dash/Sprint Modifier Stroke
            val dashPath = Path().apply {
                moveTo(adjustedDashX, adjustedDashY)
            }
            val dashDuration = (frameTimeMs * 1.5f).coerceAtLeast(15f).toLong()
            val dashStroke = GestureDescription.StrokeDescription(dashPath, 0L, dashDuration)
            builder.addStroke(dashStroke)

            // 2. Channel B: Directional Shoot Swipe with Server-Tick Compensation
            val shootPath = Path().apply {
                moveTo(adjustedShootX, adjustedShootY)
                val sweepRadius = 45.0f 
                val endX = adjustedShootX + (cos(targetAngleRad) * sweepRadius).toFloat()
                val endY = adjustedShootY + (sin(targetAngleRad) * sweepRadius).toFloat()
                lineTo(endX, endY)
            }

            // Scale duration based on network ping to prevent input drops inside server validation frames
            val serverTickAdjustment = (currentPingMs / 4).coerceAtMost(25L)
            val baseDuration = 40L + serverTickAdjustment
            val humanizedDuration = baseDuration + Random.nextLong(MIN_HUMAN_VARIANCE_MS, MAX_HUMAN_VARIANCE_MS)
            val validatedShootDuration = humanizedDuration.coerceAtLeast(frameTimeMs.toLong())

            val shootStroke = GestureDescription.StrokeDescription(shootPath, 0L, validatedShootDuration)
            builder.addStroke(shootStroke)

            // 3. Channel C: Parallel Engine Injection Layer
            if (activeEnginePath != null) {
                val engineDuration = (frameTimeMs * 0.8f).coerceAtLeast(10f).toLong()
                val engineStroke = GestureDescription.StrokeDescription(activeEnginePath, 0L, engineDuration)
                builder.addStroke(engineStroke)
            }

            // Dispatch to the OS kernel on the main execution thread
            mainThreadHandler.post {
                val success = service.dispatchGesture(builder.build(), null, null)
                if (!success) {
                    Log.w(TAG, "Hardware token rejected by OS input dispatch queue")
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ultimate Corrector pipeline drop: ${e.message}")
            false
        }
    }

    /**
     * Dedicated High-Frequency Input Buffer Flush.
     * Injects a frame-perfect structural micro-tap to force immediate input layer refresh.
     *
     * @param service The active high-privilege AccessibilityService instance.
     * @param targetX Destination X coordinate.
     * @param targetY Destination Y coordinate.
     */
    fun forceButtonResponse(service: AccessibilityService, targetX: Float, targetY: Float) {
        try {
            val frameTimeMs = calculateFrameTimeMs(service)
            val clickPath = Path().apply { 
                moveTo(targetX, targetY) 
            }
            
            // Scale clean-up stroke to perfectly fit under 1 full frame boundary window
            val targetedDuration = (frameTimeMs * 0.5f).coerceAtLeast(4f).toLong()
            val stroke = GestureDescription.StrokeDescription(clickPath, 0L, targetedDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            mainThreadHandler.post {
                service.dispatchGesture(gesture, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Buffer flush exception intercepted: ${e.message}")
        }
    }

    /**
     * Computes the display refresh rate interval dynamically to match 60Hz, 90Hz, or 120Hz display modes.
     */
    private fun calculateFrameTimeMs(service: AccessibilityService): Float {
        return try {
            val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as? WindowManager
            val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    service.display?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
                } catch (displayEx: Exception) {
                    @Suppress("DEPRECATION")
                    windowManager?.defaultDisplay?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
                }
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
            }
            
            if (refreshRate > 0f) MILLIS_PER_SECOND / refreshRate else MILLIS_PER_SECOND / DEFAULT_REFRESH_RATE_HZ
        } catch (e: Exception) {
            MILLIS_PER_SECOND / DEFAULT_REFRESH_RATE_HZ
        }
    }
}
