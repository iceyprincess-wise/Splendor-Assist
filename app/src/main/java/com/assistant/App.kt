package com.assistant

import com.assistant.storage.SplendorStorageRoot
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.GlobalCrashHandler
import android.app.Application
import android.os.Build
import java.io.File

class App : Application() {

    private fun getCurrentProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName() ?: ""
        } else {
            try {
                File("/proc/self/cmdline").readText().trim()
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        val currentProcess = getCurrentProcessName()
        val isMainProcess = currentProcess == packageName
        val isBackgroundProcess = currentProcess.endsWith(":survival") || currentProcess.endsWith(":telemetry")

        // 1. CANONICAL INITIALIZATION (Shared across all processes)
        SplendorStorageRoot.initialize()
        RuntimeLogger.initialize(this)
        RuntimeLogger.reconcileExpired()
        GlobalCrashHandler.install(this)

        if (isMainProcess) {
            // 2. UI & CONTROL ROOM ISOLATION (Main Process ONLY)
            DeathWatch.install(this)
            com.assistant.controlroom.ControlRoomBootstrap.initialize()

            runCatching {
                com.assistant.adapter.smartassist.InAppAgentCore.start()
            }
        } else if (isBackgroundProcess) {
            // 3. HEADLESS ADAPTER SURVIVAL (Background Processes ONLY)
            RuntimeLogger.log("App.onCreate: Headless process initialized [$currentProcess]", "BOOT")
        }
    }
}
