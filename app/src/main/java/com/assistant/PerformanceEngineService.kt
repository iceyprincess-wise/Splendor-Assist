package com.assistant

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.AdapterSignalBus

class PerformanceEngineService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            try {
                AdapterHealthRegistry.update(
                    AdapterHealthSnapshot(
                        adapterName = "performance_engine",
                        status = "ACTIVE",
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = 0,
                        recoveryCount = 0,
                        details = "Consolidated performance domain active"
                    )
                )
            } catch (e: Exception) {
                RuntimeLogger.log("PerfEngine telemetry error: ${e.message}", "ERROR")
            }
            workerHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("PerformanceEngineService started (Consolidated domain)", "ENGINE")

        NodeNotificationHub.attach(this, "performance_engine")

        try { NetProbeEngine.start(this) } catch (_: Throwable) {}
        try { InputLatencyEngine.start() } catch (_: Throwable) {}
        try { StutterPulseEngine.start() } catch (_: Throwable) {}
        
        workerThread = HandlerThread("PerfEngineWorker", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        workerHandler = Handler(workerThread.looper)
        workerHandler.post(telemetryRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { NetProbeEngine.stop() } catch (_: Throwable) {}
        try { InputLatencyEngine.stop() } catch (_: Throwable) {}
        try { workerThread.quitSafely() } catch (_: Throwable) {}
        RuntimeLogger.log("PerformanceEngineService destroyed", "ENGINE")
    }
}
