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

class GameplayEngineService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            try {
                AdapterHealthRegistry.update(
                    AdapterHealthSnapshot(
                        adapterName = "gameplay_engine",
                        status = "ACTIVE",
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = 0,
                        recoveryCount = 0,
                        details = "Consolidated gameplay domain active"
                    )
                )
            } catch (e: Exception) {
                RuntimeLogger.log("GameplayEngine telemetry error: ${e.message}", "ERROR")
            }
            workerHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("GameplayEngineService started (Consolidated domain)", "ENGINE")

        NodeNotificationHub.attach(this, "gameplay_engine")

        workerThread = HandlerThread("GameplayEngineWorker", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        workerHandler = Handler(workerThread.looper)
        workerHandler.post(telemetryRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { workerThread.quitSafely() } catch (_: Throwable) {}
        RuntimeLogger.log("GameplayEngineService destroyed", "ENGINE")
    }
}
