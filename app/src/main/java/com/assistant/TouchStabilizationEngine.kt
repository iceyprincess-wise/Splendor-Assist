package com.assistant

import android.accessibilityservice.AccessibilityService




































import com.assistant.runtime.*


























import kotlin.math.*







import kotlin.math.max




















object TouchStabilizationEngine {

    // L6 (batch 1, DECIDED not-needed): this object is a
    // DIRECT-EXECUTE bypass — injectZeroLatencyTap / injectStabilizedSwipe
    // inject gestures via NativeInputBridge, bypassing
    // HybridExecutionTerminal.route() (DispatchBounds clamp + gates).
    // It has ZERO callers (verified by grep: the only
    // "TouchStabilizationEngine" mentions outside this file are the
    // footer marker in ThroughPassSanitizer.kt, not a call; the live
    // zero-latency path is LatencyDefeatingInputEngine, which submits
    // to the bus). Do NOT wire it into the live path: wiring it would
    // re-open the ghost-touch vector batch G closed. Kept (not deleted)
    // per the no-delete rule; the batch-1 harness pins its caller-less
    // state. The choke point is already single-route for all live
    // traffic — L6 is closed as not-needed, not implemented.
    // Absolute minimum time required by Android for a gesture (forced to 2ms for near-instant latency)
    private const val OVERRIDE_LATENCY_MS = 2L
    
    /**
     * Injects a near-instantaneous touch down/up event to simulate zero-latency response.
     */
    @JvmStatic
    fun injectZeroLatencyTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        // Build the gesture with absolute minimal duration for instant registration
        // Native bridge bypasses Path allocation for true zero-latency injection
        return com.assistant.input.NativeInputBridge.injectTap(service, x, y)
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
        // Ensure duration doesn't violate engine bounds but pushes the hardware limit
        val safeDuration = max(OVERRIDE_LATENCY_MS, durationMs)
        return com.assistant.input.NativeInputBridge.injectSwipe(service, startX, startY, endX, endY, safeDuration)
    }
}

/* ========
TrackedPlayer
======== */
