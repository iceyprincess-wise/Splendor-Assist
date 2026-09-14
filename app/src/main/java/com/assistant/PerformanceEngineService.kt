package com.assistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

class PerformanceEngineService : Service() {

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("PerformanceEngineService started (Consolidated domain)", "ENGINE")

        NodeNotificationHub.attach(this, "performance_engine")

        // IGNITE CONSOLIDATED PERFORMANCE ENGINES
        try { PerformanceTelemetryRegistry.initialize(this) } catch (_: Throwable) {}
        try { DisplayProfileEngine.detect(this) } catch (_: Throwable) {}
        try { FramePacingEngine.start() } catch (_: Throwable) {}
        try { MainThreadStallEngine.start() } catch (_: Throwable) {}
        try { LagVerdictEngine.start() } catch (_: Throwable) {}
        try { LoadShedGovernor.start() } catch (_: Throwable) {}
        try { LoadShedCaptureBrakeEngine.start() } catch (_: Throwable) {}
        try { ThermalPeekEngine.init(this) } catch (_: Throwable) {}
        try { CpuGovernorEngine.start() } catch (_: Throwable) {}
        try { GcStallEngine.start() } catch (_: Throwable) {}
        try { RenderThreadStallEngine.start() } catch (_: Throwable) {}
        try { NetJitterEngine.start() } catch (_: Throwable) {}
        try { NetProbeEngine.start(this) } catch (_: Throwable) {}
        try { InputLatencyEngine.start() } catch (_: Throwable) {}
        try { StutterPulseEngine.start() } catch (_: Throwable) {}
        try { ActionWindowEngine.start() } catch (_: Throwable) {}
        try { BurstForensicsEngine.startDecay() } catch (_: Throwable) {}
        try { MemoryMonitorEngine.start(this) } catch (_: Throwable) {}
        
        // Start consolidated scheduler for adapter telemetry
        try { PerformanceScheduler.start(this) } catch (_: Throwable) {}
        
        RuntimeLogger.log("Performance engine stack ignited: 15 engines + scheduler", "ENGINE")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { PerformanceScheduler.stop() } catch (_: Throwable) {}
        try { NetProbeEngine.stop() } catch (_: Throwable) {}
        try { InputLatencyEngine.stop() } catch (_: Throwable) {}
        try { StutterPulseEngine.stop() } catch (_: Throwable) {}
        try { ActionWindowEngine.stop() } catch (_: Throwable) {}
        try { BurstForensicsEngine.stopDecay() } catch (_: Throwable) {}
        try { MemoryMonitorEngine.stop() } catch (_: Throwable) {}
        try { FramePacingEngine.stop() } catch (_: Throwable) {}
        try { MainThreadStallEngine.stop() } catch (_: Throwable) {}
        try { LagVerdictEngine.stop() } catch (_: Throwable) {}
        try { LoadShedGovernor.stop() } catch (_: Throwable) {}
        try { LoadShedCaptureBrakeEngine.stop() } catch (_: Throwable) {}
        try { CpuGovernorEngine.stop() } catch (_: Throwable) {}
        try { GcStallEngine.stop() } catch (_: Throwable) {}
        try { RenderThreadStallEngine.stop() } catch (_: Throwable) {}
        try { NetJitterEngine.stop() } catch (_: Throwable) {}
        
        NodeNotificationHub.detach(this, "performance_engine")
        RuntimeLogger.log("PerformanceEngineService destroyed", "ENGINE")
    }
}
