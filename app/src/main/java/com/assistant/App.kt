package com.assistant

import com.assistant.storage.SplendorStorageRoot

import com.assistant.diagnostic.RuntimeLogger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SplendorStorageRoot.initialize()
        RuntimeLogger.initialize(this)
        RuntimeLogger.reconcileExpired()

        com.assistant.controlroom.ControlRoomBootstrap.initialize()

        // Single application-wide in-app agent.
        // Application-owned so Activity recreation, room navigation,
        // and configuration changes cannot own or duplicate the agent.
        runCatching {
            com.assistant.adapter.smartassist.InAppAgentCore.start()
        }

        // Install crash catcher FIRST - before anything else
        GlobalCrashHandler.install(this)

        // catches SIGKILL deaths no exception handler can see
        DeathWatch.install(this)

        // Initialize forensic runtime logger
        com.assistant.diagnostic.RuntimeLogger.initialize(this)
        // Optional: baseline health snapshot
        GlobalCrashHandler.logFeatureFault("BOOT", "App.onCreate - health baseline")
    }
}
