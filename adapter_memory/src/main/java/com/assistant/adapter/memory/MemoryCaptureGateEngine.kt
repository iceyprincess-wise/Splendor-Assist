package com.assistant.adapter.memory

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * MemoryCaptureGateEngine — memory pressure → capture cadence bridge.
 *
 * PROVEN GAP: AdapterSignalBus.memoryIsCritical is published but has
 * zero consumers in OverlayService's capture loop. Memory at 282MB avail
 * (CRITICAL tier) does not trigger any change in OCR cadence or frame
 * capture rate. The system keeps running 30fps OCR regardless of RAM state.
 *
 * This engine translates the memory tier into a capture_throttle level
 * that OverlayService can read to widen its frame gate:
 *
 *   HEALTHY  → captureThrottle = 0  (33ms gate, 30fps)
 *   WATCH    → captureThrottle = 1  (50ms gate, 20fps)
 *   PRESSURE → captureThrottle = 2  (66ms gate, 15fps)
 *   CRITICAL → captureThrottle = 3  (100ms gate, 10fps) + skip full VisionCore alternation
 *
 * The throttle level is a simple Int on AdapterSignalBus (added below).
 * OverlayService reads it in its frame gate check.
 *
 * Additionally publishes the current throttle state to the RuntimeLogger
 * on every tier change so the heal log captures the transition.
 */
object MemoryCaptureGateEngine {

    @Volatile var captureThrottle = 0; private set
    @Volatile private var lastTier = "UNKNOWN"

    /**
     * Called by MemoryAdapterService every time it computes a tier.
     * Tier string matches MemoryAdapterService.Tier enum names.
     */
    fun onTierChange(tier: String, availMb: Long) {
        val newThrottle = when (tier) {
            "CRITICAL" -> 3
            "PRESSURE" -> 2
            "WATCH"    -> 1
            else       -> 0   // HEALTHY
        }

        val changed = newThrottle != captureThrottle || tier != lastTier
        captureThrottle = newThrottle
        lastTier = tier

        // Publish via AdapterSignalBus extended field (see bus patch below)
        AdapterSignalBus.publishCaptureThrottle(newThrottle)

        if (changed && newThrottle > 0) {
            RuntimeLogger.log(
                "MemoryCaptureGate: tier=$tier avail=${availMb}MB → " +
                    "captureThrottle=$newThrottle (capture rate reduced)",
                "MEMORY"
            )
        } else if (changed && newThrottle == 0) {
            RuntimeLogger.log(
                "MemoryCaptureGate: tier=HEALTHY avail=${availMb}MB → " +
                    "captureThrottle=0 (full rate restored)",
                "MEMORY"
            )
        }
    }

    /**
     * Returns the recommended capture frame interval in ms for the
     * current throttle level. OverlayService calls this in its frame gate.
     */
    fun recommendedIntervalMs(): Long {
        if (AdapterSignalBus.manualPerformanceEscalation) {
            // Manual player truth requests faster capture-health reassessment,
            // while the existing memory throttle remains authoritative.
            return minOf(
                when (captureThrottle) {
                    3    -> 100L
                    2    -> 66L
                    1    -> 50L
                    else -> 33L
                },
                33L
            )
        }

        return when (captureThrottle) {
            3    -> 100L
            2    -> 66L
            1    -> 50L
            else -> 33L
        }
    }

    /**
     * Returns true if full VisionCore processing should be skipped
     * (CRITICAL tier only — run ball-only detection even on even frames).
     */
    fun shouldSkipFullVision(): Boolean = captureThrottle >= 3
}
