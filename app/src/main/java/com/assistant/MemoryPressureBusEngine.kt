package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























object MemoryPressureBusEngine {
    fun publish(tier: String, availMb: Long) {
        AdapterSignalBus.publishMemory(tier, availMb)
        try { MemoryCaptureGateEngine.onTierChange(tier, availMb) } catch (_: Throwable) {}
        if (tier == "CRITICAL")
            RuntimeLogger.log("MemoryPressureBus: CRITICAL (avail=${availMb}MB)", "MEMBUSENGINE")
    }
}






/* ========
CallOverlayRepository
======== */
