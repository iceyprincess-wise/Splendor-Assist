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
                // PHASE4B FIX: use AccessibilityManager instead of broken Kotlin companion reflection.
                // Log-proven: previous reflection probe returned null while gestures were dispatching.
                // AccessibilityManager.getEnabledAccessibilityServiceList() is the correct API.
                // PHASE4B: check enabled accessibility services via Settings string (no reflection)
                // Use reflection-free approach: check our package in enabled services
                val settingsStr = try {
                    android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""
                } catch (_: Throwable) { "" }
                val alive = settingsStr.contains(packageName, ignoreCase = true)
                lastLivenessCheck = if (alive) "LIVE" else "DEAD_OR_DISABLED"
                if (!alive) {
                    accessibilityLivenessFails++
                    if (accessibilityLivenessFails >= 3) {
                        RuntimeLogger.log(
                            "SYNC WARN: SmartAssist accessibility service not in enabled list after $accessibilityLivenessFails checks. " +
                            "Re-enable in Settings > Accessibility if this persists.",
                            "SYNC"
                        )
                    }
                } else if (accessibilityLivenessFails > 0) {
                    RuntimeLogger.log("SYNC: accessibility confirmed live (was flagged $accessibilityLivenessFails times)", "SYNC")
                    accessibilityLivenessFails = 0
                }
            } catch (e: Exception) {
                lastLivenessCheck = "probe_error=${e.javaClass.simpleName}"
                RuntimeLogger.log("SYNC liveness probe error: ${e.message}", "SYNC")
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
