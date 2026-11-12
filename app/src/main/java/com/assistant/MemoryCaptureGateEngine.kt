package com.assistant


































import com.assistant.VisionTrust
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























object MemoryCaptureGateEngine {

    @Volatile var captureThrottle = 0; private set
    @Volatile var targetFps: Int = 60; private set

    fun setTargetFps(fps: Int) { targetFps = if (fps in setOf(30, 45, 60)) fps else 60 }
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
    // V10 LATENCY FIX: Cap interval at 33ms for gameplay freshness.
    // Memory pressure must not starve the decision loop of fresh frames.
    fun recommendedIntervalMs(): Long {
        val memoryInterval = when (captureThrottle) {
            3 -> 100L
            2 -> 66L
            1 -> 50L
            else -> 33L
        }
        // While the game is foregrounded, memory shedding may not lower the
        // configured minimum capture rate. Outside a match the memory interval wins.
        return if (com.assistant.VisionTrust.isGameForeground())
            minOf(memoryInterval, 1000L / targetFps) else memoryInterval
    }

    /**
     * Returns true if full VisionCore processing should be skipped
     * (CRITICAL tier only — run ball-only detection even on even frames).
     */
    fun shouldSkipFullVision(): Boolean = captureThrottle >= 3
}

/* ========
MemoryMonitorEngine
======== */
