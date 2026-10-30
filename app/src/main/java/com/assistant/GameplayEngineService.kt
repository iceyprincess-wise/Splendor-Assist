package com.assistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub

class GameplayEngineService : Service() {

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("GameplayEngineService started (Consolidated domain)", "ENGINE")

        NodeNotificationHub.attach(this, "gameplay_engine")

        // IGNITE CONSOLIDATED GAMEPLAY ENGINES & STATE STORES
        // SPLENDOR_F34_ERRORS: report registration failures loudly instead of swallowing.
        try {
            AppContributorRegistration.ensureRegistered()
        } catch (t: Throwable) {
            RuntimeLogger.log("CRITICAL: AppContributorRegistration.ensureRegistered failed: ${t.message}", "ENGINE_FAULT")
        }
        try {
            AccessibilitySurvivalEngine.getInstance(this).protect()
        } catch (t: Throwable) {
            RuntimeLogger.log("CRITICAL: AccessibilitySurvivalEngine.protect failed: ${t.message}", "ENGINE_FAULT")
        }
        
        RuntimeLogger.log("Gameplay domain ignited", "ENGINE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { AccessibilitySurvivalEngine.getInstance(this).release() } catch (_: Throwable) {}
        NodeNotificationHub.detach(this, "gameplay_engine")
        RuntimeLogger.log("GameplayEngineService destroyed", "ENGINE")
    }
}
