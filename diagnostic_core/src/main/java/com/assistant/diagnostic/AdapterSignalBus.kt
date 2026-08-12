package com.assistant.diagnostic

/**
 * AdapterSignalBus — hive cross-adapter signal channel.
 * Bodyguard adapters publish here. SmartAssist reads non-blocking every frame.
 */
object AdapterSignalBus {
    @Volatile var netWindow: String = "UNKNOWN"; private set
    @Volatile var lagVerdict: String = "UNKNOWN"; private set
    @Volatile var stutterState: String = "UNKNOWN"; private set

    fun publishNet(verdict: String) { netWindow = verdict }
    fun publishLag(verdict: String) { lagVerdict = verdict }
    fun publishStutter(state: String) { stutterState = state }

    val netIsHold: Boolean    get() = netWindow == "HOLD"
    val lagIsChoking: Boolean get() = lagVerdict == "CHOKING"
    val stutterIsSevere: Boolean get() = stutterState == "SEIZURE"
    val environmentHostile: Boolean get() = netIsHold || lagIsChoking || stutterIsSevere
}
