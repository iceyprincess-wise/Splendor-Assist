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
        try { AppContributorRegistration.ensureRegistered() } catch (_: Throwable) {}
        try { AccessibilitySurvivalEngine.getInstance(this).protect() } catch (_: Throwable) {}
        
        RuntimeLogger.log("Gameplay domain ignited", "ENGINE")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { AccessibilitySurvivalEngine.getInstance(this).release() } catch (_: Throwable) {}
        NodeNotificationHub.detach(this, "gameplay_engine")
        RuntimeLogger.log("GameplayEngineService destroyed", "ENGINE")
    }
}
