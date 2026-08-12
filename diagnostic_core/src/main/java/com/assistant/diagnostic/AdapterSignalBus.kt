package com.assistant.diagnostic

/**
 * AdapterSignalBus — hive cross-adapter signal channel.
 * Bodyguard adapters publish here. SmartAssist reads non-blocking every frame.
 */
object AdapterSignalBus {
    @Volatile var netWindow: String = "UNKNOWN"; private set
    @Volatile var lagVerdict: String = "UNKNOWN"; private set
    @Volatile var stutterState: String = "UNKNOWN"; private set

    @Volatile var memoryTier: String = "UNKNOWN"; private set
    @Volatile var memoryAvailMb: Long = -1L; private set
    fun publishNet(verdict: String) { netWindow = verdict }
    fun publishMemory(tier: String, availMb: Long) { memoryTier = tier; memoryAvailMb = availMb }
    @Volatile var inputClassification: String = "UNKNOWN"; private set
    @Volatile var inputLatencyMs: Long = 0L; private set
    fun publishInput(classification: String, latencyMs: Long) { inputClassification = classification; inputLatencyMs = latencyMs }
    val inputIsLagging: Boolean get() = inputClassification == "LAGGING"
    val memoryIsCritical: Boolean get() = memoryTier == "CRITICAL"
    val memoryIsUnderPressure: Boolean get() = memoryTier == "CRITICAL" || memoryTier == "PRESSURE"
    fun publishLag(verdict: String) { lagVerdict = verdict }
    fun publishStutter(state: String) { stutterState = state }

    val netIsHold: Boolean    get() = netWindow == "HOLD"
    val lagIsChoking: Boolean get() = lagVerdict == "CHOKING"
    val stutterIsSevere: Boolean get() = stutterState == "SEIZURE"
    val environmentHostile: Boolean get() = netIsHold || lagIsChoking || stutterIsSevere
}
