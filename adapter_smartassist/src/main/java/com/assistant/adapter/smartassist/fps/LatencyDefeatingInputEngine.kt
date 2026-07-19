package com.assistant.adapter.smartassist.fps

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import java.util.concurrent.ThreadLocalRandom

/**
 * High-performance, low-overhead gesture injector optimized for 60Hz/120Hz display 
 * sync, micro-gesture humanization, and server-tick alignment.
 */
class LatencyDefeatingInputEngine(
    private val service: AccessibilityService
) {
    // Thread-safe main thread handler for dispatching gestures
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Explicit reusable Path instance to eliminate garbage collection allocations inside hot loops
    private val preAllocatedPath = Path()

    // Configuration constants for humanization and display pacing
    private companion object {
        const val DEFAULT_FRAME_TIME_MS = 8.33f // 120Hz baseline profile
        const val SERVER_TICK_WINDOW_MS = 33L   // Standard 30Hz server tick boundary
        const val MIN_STROKE_DURATION_MS = 1L  // Absolute minimum hardware input duration
    }

    /**
     * Injects an optimized swipe gesture tailored to display refresh rates, network conditions,
     * and human hand latency characteristics.
     *
     * @param startX Starting X coordinate in screen space pixels.
     * @param startY Starting Y coordinate in screen space pixels.
     * @param endX Ending X coordinate in screen space pixels.
     * @param endY Ending Y coordinate in screen space pixels.
     * @param restrictedDuration Target execution timeline provided by the controller.
     * @param currentPingMs Dynamic network ping value used to compute server tick padding.
     */
    fun injectZeroLatencySwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        restrictedDuration: Long,
        currentPingMs: Int = 40
    ) {
        // 1. Detect dynamic hardware refresh rate to calibrate step generation
        val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display: Display? = windowManager.defaultDisplay
        val refreshRate = display?.refreshRate ?: 60.0f
        val frameTimeMs = if (refreshRate > 0f) 1000.0f / refreshRate else DEFAULT_FRAME_TIME_MS

        // 2. Apply Adaptive Noise Humanization via micro-variations
        val random = ThreadLocalRandom.current()
        val displacementAngle = random.nextDouble(0.0, 2.0 * Math.PI)
        val jitterMagnitude = random.nextDouble(0.2, 1.4) // Absolute sub-pixel boundary offset
        val humanizedStartX = (startX + (cos(displacementAngle) * jitterMagnitude)).toFloat()
        val humanizedStartY = (startY + (sin(displacementAngle) * jitterMagnitude)).toFloat()
        val humanizedEndX = (endX + (cos(displacementAngle + Math.PI) * jitterMagnitude)).toFloat()
        val humanizedEndY = (endY + (sin(displacementAngle + Math.PI) * jitterMagnitude)).toFloat()

        // 3. Server-Tick Sync: Align length and hold parameters to hit client/server possession packets
        val baseDistance = hypot((humanizedEndX - humanizedStartX).toDouble(), (humanizedEndY - humanizedStartY).toDouble())
        val dynamicScaleFactor = if (currentPingMs > 100) 1.12f else 1.0f
        val calibratedEndX = if (baseDistance > 0) {
            (humanizedStartX + (humanizedEndX - humanizedStartX) * dynamicScaleFactor)
        } else {
            humanizedEndX
        }
        val calibratedEndY = if (baseDistance > 0) {
            (humanizedStartY + (humanizedEndY - humanizedStartY) * dynamicScaleFactor)
        } else {
            humanizedEndY
        }

        // Adjust temporal bounds to match network tick rate
        val tickRemainder = restrictedDuration % SERVER_TICK_WINDOW_MS
        val tickCorrectedDuration = if (currentPingMs > 80 && tickRemainder != 0L) {
            (restrictedDuration + (SERVER_TICK_WINDOW_MS - tickRemainder)).coerceAtLeast(MIN_STROKE_DURATION_MS)
        } else {
            restrictedDuration.coerceAtLeast(MIN_STROKE_DURATION_MS)
        }

        // 4. Construct high-frequency micro-gesture stroke path
        preAllocatedPath.reset()
        preAllocatedPath.moveTo(humanizedStartX, humanizedStartY)

        // Interpolate path segments along display frame markers for perfect 60Hz/120Hz registration
        val totalSteps = (tickCorrectedDuration / frameTimeMs).coerceAtLeast(1.0f)
        val stepCount = totalSteps.toInt()

        if (stepCount > 1) {
            for (i in 1 until stepCount) {
                val alpha = i.toFloat() / totalSteps
                val intermediateX = humanizedStartX + (calibratedEndX - humanizedStartX) * alpha
                val intermediateY = humanizedStartY + (calibratedEndY - humanizedStartY) * alpha
                preAllocatedPath.lineTo(intermediateX, intermediateY)
            }
        }
        preAllocatedPath.lineTo(calibratedEndX, calibratedEndY)

        // 5. Generate and dispatch structural GestureDescription
        val stroke = GestureDescription.StrokeDescription(
            preAllocatedPath,
            0L,
            tickCorrectedDuration
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        // Execute injection on the main application looper thread to satisfy Android security constraints
        mainHandler.post {
            try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            synchronized(preAllocatedPath) {
                                preAllocatedPath.reset()
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            synchronized(preAllocatedPath) {
                                preAllocatedPath.reset()
                            }
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                synchronized(preAllocatedPath) {
                    preAllocatedPath.reset()
                }
            }
        }
    }
}
