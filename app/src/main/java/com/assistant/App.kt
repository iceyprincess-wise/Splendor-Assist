package com.assistant

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        com.assistant.controlroom.ControlRoomBootstrap.initialize()

        // Install crash catcher FIRST - before anything else
        GlobalCrashHandler.install(this)

        // Initialize forensic runtime logger
        com.assistant.diagnostic.RuntimeLogger.initialize(this)
        // Optional: baseline health snapshot
        GlobalCrashHandler.logFeatureFault("BOOT", "App.onCreate - health baseline")
    }
}
