package com.assistant

import com.assistant.storage.SplendorStorageRoot
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.LogMirror
import android.content.ComponentCallbacks2
import android.app.Application
import android.os.Build

class App : Application() {

    companion object {
        // FIX #5: Centralized process role suffixes to prevent silent role drift
        const val ROLE_SUFFIX_SURVIVAL = ":survival"
        const val ROLE_SUFFIX_TELEMETRY = ":telemetry"
    }

    private fun installF47CrashMirror() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = java.io.StringWriter().also { writer ->
                    error.printStackTrace(java.io.PrintWriter(writer))
                }.toString()
                LogMirror.mirrorCrashAndWait("F47CRASH", "F47CRASH\n$trace")
            } catch (_: Throwable) {
                // Crash reporting is best-effort and must not replace the process failure.
            }
            try { previous?.uncaughtException(thread, error) } catch (_: Throwable) {}
        }
    }

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
        LogMirror.init(this)
        GlobalCrashHandler.install(this)
        installF47CrashMirror()
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = DiagnosticSamplers.onTrimMemory(level)
            override fun onLowMemory() = DiagnosticSamplers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        })
        DiagnosticSamplers.start(this)
        try { CrashForensics.install() } catch (_: Throwable) {}

        val currentProcess = getCurrentProcessName()
        val isMainProcess = currentProcess == packageName
        // FIX #5: Use centralized constants to prevent silent role drift
        val isBackgroundProcess = currentProcess.endsWith(ROLE_SUFFIX_SURVIVAL) || currentProcess.endsWith(ROLE_SUFFIX_TELEMETRY)

        // FIX #2: Capture storage initialization result to detect degraded bootstrap state
        val storageReady = SplendorStorageRoot.initialize()
        if (!storageReady) {
            faultSafe("SplendorStorageRoot", "External storage unavailable. Degraded bootstrap state.")
        }

        RuntimeLogger.initialize(this)
        com.assistant.memory.MmapStateEngine.initialize(cacheDir)

        // Domain Backup Generator: Forces class loading for all 216+ engine objects
        // and verifies SMART_ASSIST (90) and STUTTER (80) execution sources.
        // FIX #1: Removed duplicate RuntimeLogger.reconcileExpired() since initialize() already calls it

        // GlobalCrashHandler must be installed everywhere to catch process-wide crashes

        if (isMainProcess) {
            DeathWatch.install(this)
            com.assistant.diagnostic.TruthLogcatEngine.start()
            com.assistant.controlroom.ControlRoomBootstrap.initialize()
            com.assistant.controlroom.AdapterControlRoomSync.start(this)

            if (!storageReady) {
                logSafe("App.onCreate: IN-APP AGENT SKIPPED - Storage not ready", "BOOT")
            } else {
                val agentStarted = try {
                    com.assistant.InAppAgentCore.tryStart()
                } catch (t: Throwable) {
                    val reason = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                    logSafe("IN-APP AGENT FAILED TO START: $reason", "BOOT")
                    faultSafe("InAppAgentCore.start", reason)
                    false
                }

                if (agentStarted && com.assistant.InAppAgentCore.isRunning()) {
                    logSafe("App.onCreate: InAppAgentCore bootstrap verified running", "BOOT")
                } else {
                    val snapshotRunning = try {
                        com.assistant.InAppAgentCore.snapshot().running
                    } catch (_: Throwable) {
                        false
                    }
                    val reason = "started=$agentStarted snapshotRunning=$snapshotRunning"
                    logSafe("App.onCreate: IN-APP AGENT NOT RUNNING after bootstrap ($reason)", "BOOT")
                    faultSafe("InAppAgentCore.start", "agent not running after bootstrap ($reason)")
                }
            }
        } else if (isBackgroundProcess) {
            logSafe("App.onCreate: Headless process initialized [$currentProcess]", "BOOT")
        }
    }
}
