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

    // PHASE3: new adapter signals
    @Volatile var thermalStatus: Int = 0; private set      // 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN
    @Volatile var batteryLevel: Int = 100; private set
    @Volatile var batteryCharging: Boolean = true; private set
    @Volatile var pingQuality: String = "UNKNOWN"; private set
    @Volatile var deviceBootStable: Boolean = false; private set
    @Volatile var fleetDegraded: Boolean = false; private set  // true when offline adapters > 2

    fun publishThermal(status: Int) { thermalStatus = status }
    fun publishBattery(level: Int, charging: Boolean) { batteryLevel = level; batteryCharging = charging }
    fun publishPing(quality: String) { pingQuality = quality }
    fun publishBootState(stable: Boolean) { deviceBootStable = stable }
    fun publishFleet(offlineCount: Int) { fleetDegraded = offlineCount > 2 }

    val thermalIsSevere: Boolean get() = thermalStatus >= 3
    val batteryCritical: Boolean get() = batteryLevel < 15 && !batteryCharging
    val netIsHold: Boolean    get() = netWindow == "HOLD"
    val lagIsChoking: Boolean get() = lagVerdict == "CHOKING"
    val stutterIsSevere: Boolean get() = stutterState == "SEIZURE"
    val environmentHostile: Boolean get() = netIsHold || lagIsChoking || stutterIsSevere || thermalIsSevere

    // PHASE5B: memory -> capture bridge signal
    @Volatile var captureThrottle: Int = 0; private set
    fun publishCaptureThrottle(level: Int) { captureThrottle = level.coerceIn(0, 3) }
    val captureIsThrottled: Boolean get() = captureThrottle > 0

    // PHASE5B: load shed -> execution brake signal
    @Volatile var executionBrake: Int = 0; private set
    fun publishExecutionBrake(level: Int) { executionBrake = level.coerceIn(0, 2) }
    val executionIsBraked: Boolean get() = executionBrake > 0
    val executionIsFullyBraked: Boolean get() = executionBrake >= 2

    // CROWDING_ZONE: penalty box / corner scene detector.
    // Published by CrowdingZoneDetector (adapter_smartassist) each frame.
    // Read by LagVerdictEngine to require extra CHOKING confirmation
    // when the lag spike is from rendering load, not sustained device stress.
    @Volatile var crowdingZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set
    fun publishCrowdingZone(zone: Boolean, level: Float) {
        crowdingZone = zone
        crowdingLevel = level.coerceIn(0f, 1f)
    }
}
