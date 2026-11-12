package com.assistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

class PerformanceEngineService : Service() {

    // Heartbeat for the watchdog: this service never reported health, so
    // the watchdog saw a missing snapshot as OFFLINE and restarted it
    // every 15s forever. Report ACTIVE every 10s (same cadence as the
    // watchdog's own heartbeat); a real crash leaves a stale snapshot,
    // which effectiveStatus() then reports OFFLINE.
    private val healthHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healthHeartbeat = object : Runnable {
        override fun run() {
            try {
                com.assistant.diagnostic.registry.AdapterHealthRegistry.update(
                    com.assistant.diagnostic.registry.AdapterHealthSnapshot(
                        adapterName = "performance_engine",
                        status = "ACTIVE",
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = 0,
                        recoveryCount = 0,
                        details = "service alive"
                    )
                )
            } catch (_: Throwable) {}
            healthHandler.postDelayed(this, 10000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("PerformanceEngineService started (Consolidated domain)", "ENGINE")

        NodeNotificationHub.attach(this, "performance_engine")
        com.assistant.diagnostic.registry.AdapterHealthRegistry.initialize(this)
        healthHandler.post(healthHeartbeat)

        // IGNITE CONSOLIDATED PERFORMANCE ENGINES
        try { PerformanceTelemetryRegistry.initialize(this) } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { DisplayProfileEngine.detect(this) } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { FramePacingEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { MainThreadStallEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LagVerdictEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LoadShedGovernor.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LoadShedCaptureBrakeEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { ThermalPeekEngine.init(this) } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { CpuGovernorEngine.start(this) } catch (e: Throwable) { RuntimeLogger.log("CPU OBSERVER EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { GcStallEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { RenderThreadStallEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetJitterEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetProbeEngine.start(this) } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { InputLatencyEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { StutterPulseEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { ActionWindowEngine.start() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { BurstForensicsEngine.startDecay() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { MemoryMonitorEngine.start(this) } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        
        // Start consolidated scheduler for adapter telemetry
        try { PerformanceScheduler.start(this) } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        
        RuntimeLogger.log("Performance engine stack ignited: 15 engines + scheduler", "ENGINE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        healthHandler.removeCallbacksAndMessages(null)
        try { PerformanceScheduler.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetProbeEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { InputLatencyEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { StutterPulseEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { ActionWindowEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { BurstForensicsEngine.stopDecay() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { MemoryMonitorEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { FramePacingEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { MainThreadStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LagVerdictEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LoadShedGovernor.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LoadShedCaptureBrakeEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { CpuGovernorEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("CPU OBSERVER EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { GcStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { RenderThreadStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetJitterEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        
        NodeNotificationHub.detach(this, "performance_engine")
        RuntimeLogger.log("PerformanceEngineService destroyed", "ENGINE")
    }
}
