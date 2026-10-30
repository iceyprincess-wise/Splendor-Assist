object TouchStabilizationEngine {
    
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
/* ======
TouchStabilizationEngine Anchor
====== */

/* ========
TrackedPlayer
======== */
