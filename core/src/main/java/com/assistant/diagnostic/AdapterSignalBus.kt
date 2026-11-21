package com.assistant.diagnostic

/**
 * AdapterSignalBus — hive cross-adapter signal channel.
 * Bodyguard adapters publish here. SmartAssist reads non-blocking every frame.
 * IN-PROCESS ONLY (SPLENDOR_P7_BUS_XPROC_NOTE): this is a plain in-process
 * object, so each Android process has its own copy. Publishers in :survival
 * are invisible to readers in the main process and vice versa. Anything a
 * different process must read goes through PerformanceTelemetryRegistry
 * files (perf_device.txt / perf_aggression.txt) instead.
 *
 * ATOMIC SNAPSHOT (no42): the whole signal state is one immutable
 * [SignalSnapshot] behind a single @Volatile holder. Every publish replaces
 * the snapshot in one volatile write, so a reader can never observe a
 * half-published pair. The old code wrote multi-field publishes as two
 * separate @Volatile writes (publishBattery() wrote batteryLevel then
 * batteryCharging), so a reader between the two saw the new level with the
 * stale charging flag — the same tearing class as D29-1 /
 * ContributionRegistry D8. Multi-field readers that need one consistent
 * generation should call [snapshot()] once and read from it: separate
 * getter reads in different statements can still span two publishes.
 * Derived getters read one local copy of the state, so each derived value
 * is evaluated on a single generation. Public names, defaults and publish
 * semantics are unchanged; only the storage is atomic now.
 *
 * LIVENESS (batch 6b, v219 item e): a snapshot used to carry NO time —
 * two snapshots taken minutes apart through a dead period were
 * byte-identical, so no reader could tell a live-but-quiet bus from a
 * dead one. Every publish now stamps updatedMs (wall clock at publish)
 * through commit(); a snapshot whose updatedMs is old is stale, never
 * silently fresh. 0 means "never published".
 */
object AdapterSignalBus {

    /**
     * One immutable generation of every bus signal. Defaults are the
     * UNKNOWN sentinels: -1 for unknown numerics, "UNKNOWN" for unknown
     * strings, false for unknown flags. batteryCharging defaults to false
     * (B30-D6 class: the old `true` default claimed "charging" before any
     * battery reading existed; PerformanceTelemetryRegistry.DEVICE_EMPTY
     * was fixed the same way).
     */
    data class SignalSnapshot(
        val netWindow: String = "UNKNOWN",
        val lagVerdict: String = "UNKNOWN",
        val stutterState: String = "UNKNOWN",
        val memoryTier: String = "UNKNOWN",
        val memoryAvailMb: Long = -1L,
        val inputClassification: String = "UNKNOWN",
        val inputLatencyMs: Long = 0L,
        // 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN, -1=UNKNOWN
        // (PerformanceScheduler publishes -1 when PowerManager is null;
        // FilterEngine treats thermalStatus < 0 as missing telemetry).
        val thermalStatus: Int = 0,
        val batteryLevel: Int = -1,
        val batteryCharging: Boolean = false,
        val pingQuality: String = "UNKNOWN",
        val deviceBootStable: Boolean = false,
        val fleetDegraded: Boolean = false,
        val captureThrottle: Int = 0,
        val executionBrake: Int = 0,
        val crowdingZone: Boolean = false,
        val crowdingLevel: Float = 0f,
        // Letter A item 39: the filterAggression field is DELETED (was
        // here, default 1.0f, with its getter + publishFilterAggression
        // below). FilterEngine published it, but the getter had zero
        // readers anywhere — not even through snapshot() — because the
        // cross-process consumers (LagVerdictEngine, BurstForensicsEngine,
        // SpeedCompensationContributor) read the registry file via
        // PerformanceTelemetryRegistry.currentFilterAggression(), and the
        // bus is in-process only (see the class KDoc). The registry mirror
        // (FilterEngine.publishAggressionThrottled) is the live channel
        // and is untouched.
        // Batch 6b: wall-clock ms of the last publish (0 = never).
        // The liveness signal — see the class KDoc.
        val updatedMs: Long = 0L
    )

    @Volatile
    private var state: SignalSnapshot = SignalSnapshot()

    /** Single choke point for every publish: stamps updatedMs. */
    private fun commit(next: SignalSnapshot) {
        state = next.copy(updatedMs = System.currentTimeMillis())
    }

    /** One consistent generation of all signals; see the class KDoc. */
    fun snapshot(): SignalSnapshot = state

    val netWindow: String get() = state.netWindow
    val lagVerdict: String get() = state.lagVerdict
    val stutterState: String get() = state.stutterState

    val memoryTier: String get() = state.memoryTier
    val memoryAvailMb: Long get() = state.memoryAvailMb
    fun publishNet(verdict: String) { commit(state.copy(netWindow = verdict)) }
    fun publishMemory(tier: String, availMb: Long) { commit(state.copy(memoryTier = tier, memoryAvailMb = availMb)) }
    val inputClassification: String get() = state.inputClassification
    val inputLatencyMs: Long get() = state.inputLatencyMs
    fun publishInput(classification: String, latencyMs: Long) { commit(state.copy(inputClassification = classification, inputLatencyMs = latencyMs)) }
    // No68-I-D30: TouchQualityEngine publishes "TOUCH_IRQ_STALL" on a real
    // touch-IRQ stall, but this getter only matched "LAGGING" — so the stall
    // signal had no behavioural consumer anywhere (only diagnostic dumps
    // read the raw string). A stalled touch input IS lagging input:
    // SpeedCompensation shortens gestures 0.7x while it holds.
    val inputIsLagging: Boolean get() { val s = state; return s.inputClassification == "LAGGING" || s.inputClassification == "TOUCH_IRQ_STALL" }
    val memoryIsCritical: Boolean get() { val s = state; return s.memoryTier == "CRITICAL" }
    val memoryIsUnderPressure: Boolean get() { val s = state; return s.memoryTier == "CRITICAL" || s.memoryTier == "PRESSURE" }
    fun publishLag(verdict: String) { commit(state.copy(lagVerdict = verdict)) }
    // Parameter renamed newState (was `state`): it shadowed the snapshot holder.
    fun publishStutter(newState: String) { commit(state.copy(stutterState = newState)) }

    // PHASE3: new adapter signals
    val thermalStatus: Int get() = state.thermalStatus
    val batteryLevel: Int get() = state.batteryLevel
    val batteryCharging: Boolean get() = state.batteryCharging
    val pingQuality: String get() = state.pingQuality
    val deviceBootStable: Boolean get() = state.deviceBootStable
    val fleetDegraded: Boolean get() = state.fleetDegraded  // true when offline adapters > 2

    // Thermal is clamped to -1..6: -1 is the UNKNOWN sentinel
    // (PerformanceScheduler publishes -1 when PowerManager is null), 0..6 is
    // PowerManager.currentThermalStatus (NONE..SHUTDOWN). Clamping to 0..6
    // would have lied UNKNOWN into NONE.
    fun publishThermal(status: Int) { commit(state.copy(thermalStatus = status.coerceIn(-1, 6))) }
    fun publishBattery(level: Int, charging: Boolean) { commit(state.copy(batteryLevel = level.coerceIn(-1, 100), batteryCharging = charging)) }
    fun publishPing(quality: String) { commit(state.copy(pingQuality = quality)) }
    fun publishBootState(stable: Boolean) { commit(state.copy(deviceBootStable = stable)) }
    fun publishFleet(offlineCount: Int) { commit(state.copy(fleetDegraded = offlineCount > 2)) }

    val thermalIsSevere: Boolean get() { val s = state; return s.thermalStatus >= 3 }
    val batteryCritical: Boolean get() { val s = state; return s.batteryLevel in 0..14 && !s.batteryCharging }
    val netIsHold: Boolean get() { val s = state; return s.netWindow == "HOLD" }
    val lagIsChoking: Boolean get() { val s = state; return s.lagVerdict == "CHOKING" }
    val stutterIsSevere: Boolean get() { val s = state; return s.stutterState == "SEIZURE" }
    val environmentHostile: Boolean get() { val s = state; return s.netWindow == "HOLD" || s.lagVerdict == "CHOKING" || s.stutterState == "SEIZURE" || s.thermalStatus >= 3 }

    // PHASE5B: memory -> capture bridge signal
    val captureThrottle: Int get() = state.captureThrottle
    fun publishCaptureThrottle(level: Int) { commit(state.copy(captureThrottle = level.coerceIn(0, 3))) }

    // PHASE5B: load shed -> execution brake signal
    val executionBrake: Int get() = state.executionBrake
    fun publishExecutionBrake(level: Int) { commit(state.copy(executionBrake = level.coerceIn(0, 2))) }
    val executionIsBraked: Boolean get() { val s = state; return s.executionBrake > 0 }
    val executionIsFullyBraked: Boolean get() { val s = state; return s.executionBrake >= 2 }

    // CROWDING_ZONE: penalty box / corner scene detector.
    // Published by CrowdingZoneDetector (adapter_smartassist) each frame.
    // Read by LagVerdictEngine to require extra CHOKING confirmation
    // when the lag spike is from rendering load, not sustained device stress.
    val crowdingZone: Boolean get() = state.crowdingZone
    val crowdingLevel: Float get() = state.crowdingLevel
    fun publishCrowdingZone(zone: Boolean, level: Float) {
        commit(state.copy(crowdingZone = zone, crowdingLevel = level.coerceIn(0f, 1f)))
    }

    // FILTER ENGINE: Extremist push multiplier — Letter A item 39: the
    // bus filterAggression getter + publishFilterAggression are DELETED
    // (were here). The value was write-only on this bus; the live channel
    // is PerformanceTelemetryRegistry (see the note on the snapshot field
    // above and FilterEngine.publishAggressionThrottled).
}
