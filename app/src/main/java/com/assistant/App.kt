package com.assistant

import com.assistant.storage.SplendorStorageRoot
import com.assistant.diagnostic.RuntimeLogger
import android.app.Application
import android.os.Build

class App : Application() {

    private fun getCurrentProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName() ?: ""
        } else {
            try {
                java.io.File("/proc/self/cmdline").readText().trim()
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun logSafe(message: String, tag: String) {
        try {
            RuntimeLogger.log(message, tag)
        } catch (_: Throwable) {
        }
    }

    private fun faultSafe(feature: String, message: String) {
        try {
            GlobalCrashHandler.logFeatureFault(feature, message)
        } catch (_: Throwable) {
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

        // FIX: GlobalCrashHandler is in package com.assistant, no import needed
        GlobalCrashHandler.install(this)

        if (isMainProcess) {
            // 2. UI & CONTROL ROOM ISOLATION (Main Process ONLY)
            DeathWatch.install(this)
            com.assistant.controlroom.ControlRoomBootstrap.initialize()

            // FIX #4: silent InAppAgentCore.start() failure boundary removed.
            val agentStarted = try {
                com.assistant.adapter.smartassist.InAppAgentCore.tryStart()
            } catch (t: Throwable) {
                val reason = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                logSafe("IN-APP AGENT FAILED TO START: $reason", "BOOT")
                faultSafe("InAppAgentCore.start", reason)
                false
            }

            if (agentStarted && com.assistant.adapter.smartassist.InAppAgentCore.isRunning()) {
                logSafe("App.onCreate: InAppAgentCore bootstrap verified running", "BOOT")
            } else {
                val snapshotRunning = try {
                    com.assistant.adapter.smartassist.InAppAgentCore.snapshot().running
                } catch (_: Throwable) {
                    false
                }
                val reason = "started=$agentStarted snapshotRunning=$snapshotRunning"
                logSafe("App.onCreate: IN-APP AGENT NOT RUNNING after bootstrap ($reason)", "BOOT")
                faultSafe("InAppAgentCore.start", "agent not running after bootstrap ($reason)")
            }
        } else if (isBackgroundProcess) {
            // 3. HEADLESS ADAPTER SURVIVAL (Background Processes ONLY)
            logSafe("App.onCreate: Headless process initialized [$currentProcess]", "BOOT")
        }
    }
}
