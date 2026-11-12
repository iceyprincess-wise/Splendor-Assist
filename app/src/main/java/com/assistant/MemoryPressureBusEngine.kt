package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























object MemoryPressureBusEngine {
    fun publish(tier: String, availMb: Long) {
        AdapterSignalBus.publishMemory(tier, availMb)
        // SPLENDOR_P7_DEVICE_MEMORY_BEGIN
        // Mirror into the cross-process device snapshot so main-process
        // FilterEngine reads real memory tier / availability.
        try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceMemory(tier, availMb) } catch (_: Throwable) {}
        // SPLENDOR_P7_DEVICE_MEMORY_END
        try { MemoryCaptureGateEngine.onTierChange(tier, availMb) } catch (_: Throwable) {}
        if (tier == "CRITICAL")
            RuntimeLogger.log("MemoryPressureBus: CRITICAL (avail=${availMb}MB)", "MEMBUSENGINE")
    }
}






/* ========
CallOverlayRepository
======== */
