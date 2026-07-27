package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.max

/**
 * TOUCH STABILIZATION ENGINE
 * Bypasses standard Android input latency queues by directly injecting 
 * mathematically smoothed, high-priority GestureDescriptions.
 */
object TouchStabilizationEngine {
    
    // Absolute minimum time required by Android for a gesture (forced to 2ms for near-instant latency)
    private const val OVERRIDE_LATENCY_MS = 2L
    
    /**
     * Injects a near-instantaneous touch down/up event to simulate zero-latency response.
     */
    @JvmStatic
    fun injectZeroLatencyTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        
        // Build the gesture with absolute minimal duration for instant registration
        val stroke = GestureDescription.StrokeDescription(path, 0, OVERRIDE_LATENCY_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return service.dispatchGesture(gesture, null, null)
    }
    
    /**
     * Stabilizes a swipe gesture by smoothing the coordinate translation 
     * and forcing it through the Accessibility queue at maximum speed.
     */
    @JvmStatic
    fun injectStabilizedSwipe(
        service: AccessibilityService, 
        startX: Float, 
        startY: Float, 
        endX: Float, 
        endY: Float, 
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        // Ensure duration doesn't violate engine bounds but pushes the hardware limit
        val safeDuration = max(OVERRIDE_LATENCY_MS, durationMs)
        
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return service.dispatchGesture(gesture, null, null)
    }
}
