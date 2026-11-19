package com.assistant

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * N3 (f58): AdapterSignalBus is in-process only (SPLENDOR_P7_BUS_XPROC_NOTE).
 * The net/lag/stutter/memory publishers all run in :survival
 * (PerformanceEngineService), so the main-process copy the heal check and
 * SpeedCompensationContributor read stayed UNKNOWN forever — BUS_ALL_UNKNOWN
 * at 1535 s in the 2026-11-18 session while the :survival side was alive
 * (perf_window.txt HOLD, perf_device.txt SMOOTH|CALM|PRESSURE).
 *
 * Mirror, don't move: the :survival publishers keep publishing to their own
 * bus copy and to the registry files (unchanged). This poller, running in
 * the main process on the heal engine's cadence, copies the latest
 * cross-process values from PerformanceTelemetryRegistry into the
 * main-process bus. Stale (>15 s, the registry's own DEVICE_STALE_MS)
 * values are never mirrored — a dead :survival side must read UNKNOWN,
 * not a fossil. No value is fabricated: only values the :survival side
 * actually published are copied.
 */
object MainProcessBusMirror {
    @Volatile private var lastMirrorMs: Long = 0L

    fun mirrorIfDue(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastMirrorMs < 5000L) return
        lastMirrorMs = nowMs
        try {
            val device = PerformanceTelemetryRegistry.currentDeviceState()
            val fresh = System.currentTimeMillis() - device.updatedMs < 15_000L
            if (fresh) {
                if (device.lagVerdict != "UNKNOWN") AdapterSignalBus.publishLag(device.lagVerdict)
                if (device.stutterState != "UNKNOWN") AdapterSignalBus.publishStutter(device.stutterState)
                if (device.memoryTier != "UNKNOWN") AdapterSignalBus.publishMemory(device.memoryTier, device.memoryAvailMb)
                if (device.thermalStatus >= 0) AdapterSignalBus.publishThermal(device.thermalStatus)
                if (device.batteryLevel >= 0) AdapterSignalBus.publishBattery(device.batteryLevel, device.batteryCharging)
                AdapterSignalBus.publishExecutionBrake(device.executionBrake)
            }
            // Net window has no single-value registry reader; the history's
            // newest entry is the current verdict (publishActionWindow
            // appends; recentActionWindowHistory returns oldest-first).
            val window = PerformanceTelemetryRegistry.recentActionWindowHistory(1).lastOrNull()
            if (window != null && System.currentTimeMillis() - window.updatedMs < 15_000L) {
                AdapterSignalBus.publishNet(window.verdict)
            }
        } catch (_: Throwable) { }
    }
}
