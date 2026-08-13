package com.assistant.adapter.sync

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

/**
 * PHASE3 UPGRADE: SyncAdapterService
 * Previous state: DEAD SHELL — only a heartbeat, zero actual logic.
 * Real job now: monitors accessibility service liveness every 15s.
 * The accessibility service is the entire gesture dispatch path.
 * If it dies silently (HyperOS kills it), no gestures fire but no
 * error is logged anywhere. This adapter is the early-warning detector.
 */
class SyncAdapterService : Service() {
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastLivenessCheck = "unchecked"
    @Volatile private var accessibilityLivenessFails = 0

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_sync",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = accessibilityLivenessFails,
                    recoveryCount = 0,
                    details = "accessibility=$lastLivenessCheck fails=$accessibilityLivenessFails"
                )
            )
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val livenessRunnable = object : Runnable {
        override fun run() {
            try {
                // Check if SmartAssistAccessibilityEngine has a live instance
                val cls = Class.forName("com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine")
                val field = cls.getDeclaredField("globalInstance")
                field.isAccessible = true
                val companion = cls.getDeclaredField("Companion").get(null)
                // Access via companion
                val instance = try {
                    cls.getField("globalInstance").get(null)
                } catch (_: Throwable) {
                    // Try via companion object
                    try { field.get(companion) } catch (_: Throwable) { null }
                }
                val alive = instance != null
                lastLivenessCheck = if (alive) "LIVE" else "DEAD"
                if (!alive) {
                    accessibilityLivenessFails++
                    RuntimeLogger.log(
                        "SYNC ALERT: SmartAssistAccessibilityEngine is NULL — gesture dispatch dead. Fails=$accessibilityLivenessFails",
                        "SYNC"
                    )
                } else if (accessibilityLivenessFails > 0) {
                    RuntimeLogger.log("SYNC: accessibility restored after $accessibilityLivenessFails fails", "SYNC")
                    accessibilityLivenessFails = 0
                }
            } catch (e: Exception) {
                lastLivenessCheck = "probe_error=${e.javaClass.simpleName}"
                RuntimeLogger.log("SYNC liveness probe failed: ${e.message}", "SYNC")
            }
            heartbeatHandler.postDelayed(this, 15000)
        }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))

    override fun onCreate() {
        super.onCreate()
        NodeNotificationHub.attach(this, "adapter_sync")
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_sync",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Accessibility liveness monitor active"
            )
        )
        heartbeatHandler.post(heartbeatRunnable)
        heartbeatHandler.postDelayed(livenessRunnable, 5000L) // first check after 5s
        RuntimeLogger.log("SyncAdapterService started - ACCESSIBILITY LIVENESS MONITOR", "ADAPTER")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        NodeNotificationHub.detach(this, "adapter_sync")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
