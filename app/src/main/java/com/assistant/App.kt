package com.assistant

// F49_PATCH_V1

import com.assistant.storage.SplendorStorageRoot
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.LogMirror
import com.assistant.execution.DispatchBounds
import android.content.ComponentCallbacks2
import android.content.Context
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
        // No65-D10: LogMirror.init() takes no Context any more.
        LogMirror.init()
        GlobalCrashHandler.install(this)
        OverrideStore.initialize(this)
        EngineCreditStore.initialize(this)
        installF47CrashMirror()
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = DiagnosticSamplers.onTrimMemory(level)
            @Suppress("DEPRECATION")
            override fun onLowMemory() = DiagnosticSamplers.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        })
        DiagnosticSamplers.start(this)
        try { CrashForensics.install() } catch (t: Throwable) { android.util.Log.e("App", "CrashForensics.install failed, no crash forensics this session", t) }

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

        // L15 (batch 1): restore the persisted VisionConfiguration
        // toggles — without this a restart lost every toggle (Q68-97).
        // Runs on every process start (main + :survival); the seam reads
        // SharedPreferences, so each process restores its own copy.
        try { com.assistant.VisionConfigurationEngine.restore(applicationContext) } catch (_: Throwable) { }

        // RC1 anchor fix (2026-11-24): apply Ed's joystick-origin pref at
        // startup. The measured centre comes from his own 13 screenshots
        // (ButtonMappingEngine.BUTTONS); the pref makes it explicit,
        // logged and reversible — NOT a silent re-anchor. Default true
        // (measured); recordProbeOutcome(false) restores the anchor.
        // Runs on every process start like the restore above; the state
        // is per-process in-memory, and background processes dispatch
        // nothing, so the call is harmless there.
        // Radius re-arm: the one-shot maxDrag probe measured ~8 px from
        // the WRONG origin (the anchor) and then disarmed, locking the
        // bad radius for the session. Clear it and re-arm so the probe
        // re-measures from the corrected centre (~23 px expected). The
        // probe already reads d7JoystickCenter, so the re-anchor alone
        // un-poisons it — this clears the already-locked value.
        try {
            val joyPrefs = applicationContext.getSharedPreferences("splendor_capture", Context.MODE_PRIVATE)
            val useMeasured = joyPrefs.getBoolean("joystick_use_measured_centre", true)
            ButtonMappingEngine.recordProbeOutcome(useMeasured)
            DispatchBounds.setMaxDrag(null)
            ControlMappingTrainer.setMaxDragProbeActive(true)
            logSafe("JOY_ORIGIN verdict=${ButtonMappingEngine.probeOutcome()} source=pref", "TRAINER")
        } catch (_: Throwable) { }

        // Queue item 21 (v3 TFLite detector): initialise the trained
        // detector on a background thread — initialize() reads the 80 MB
        // model file and must never block onCreate. Main process only:
        // :survival / :telemetry run no vision, so loading the model
        // there would waste memory on a 4 GB phone. Runs after
        // SplendorStorageRoot.initialize() (file-first needs the storage
        // root) and after VisionConfigurationEngine.restore() above.
        // File-first side-load (/sdcard/Splendor-Assist/
        // splendor_detector.tflite, SHA-pinned v3); with no file present
        // the engine stays MISSING_MODEL and behaviour is identical to
        // today. The async inference loop starts only after a successful
        // load (inside initialize()).
        if (isMainProcess) {
            try {
                Thread({
                    try { TrainedDetectionEngine.initialize(applicationContext) } catch (_: Throwable) { }
                }, "tflite-init").start()
            } catch (_: Throwable) { }
        }

        // Domain Backup Generator: Forces class loading for all 216+ engine objects
        // and verifies SMART_ASSIST (90) and STUTTER (80) execution sources.
        // FIX #1: Removed duplicate RuntimeLogger.reconcileExpired() since initialize() already calls it

        // GlobalCrashHandler must be installed everywhere to catch process-wide crashes

        if (isMainProcess) {
            DeathWatch.install(this)
            com.assistant.diagnostic.TruthLogcatEngine.start()
            com.assistant.controlroom.ControlRoomBootstrap.initialize()
            com.assistant.controlroom.AdapterControlRoomSync.start(this)

            // SPLENDOR_P7_APP_START_BEGIN
            // Task 2B: start FilterEngine here, in the main process, not
            // from the frame-gated BoosterIgnition path. App.onCreate runs
            // on every process start, so after a restart FilterEngine runs
            // even when no capture frame ever arrives (the old sole start
            // path lived inside OverlayService's per-frame block). The
            // :survival process must NOT start one: App.onCreate runs
            // there too, and two FilterEngines would interleave writes to
            // the same report file. Registry initialize first: without it
            // the file fallbacks (currentNet / currentLoadShed /
            // currentDeviceState) can never resolve their files in this
            // process and every read stays at its UNKNOWN default (2A-b).
            try {
                com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.initialize(applicationContext)
            } catch (t: Throwable) {
                logSafe("App.onCreate: PerformanceTelemetryRegistry init failed: ${t.message}", "BOOT")
            }
            try {
                com.assistant.diagnostic.FilterEngine.start()
                logSafe("App.onCreate: FilterEngine started (main process)", "BOOT")
            } catch (t: Throwable) {
                faultSafe("FilterEngine.start", "${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
            // D31-5: start the adapter recovery engine here, in the main
            // process only, next to FilterEngine.start() - same reason:
            // App.onCreate runs on every process start, so recovery runs
            // even when no capture frame ever arrives (the BoosterIgnition
            // path is frame-gated). Main-process-only is load-bearing:
            // the engine's unique job is restarting adapter_watchdog,
            // which the :survival watchdog cannot do for itself, so the
            // engine must live in a process that outlives :survival.
            // start() is idempotent (started flag) and self-schedules
            // its 30s scan; the try/catch mirrors the FilterEngine block
            // above so a recovery-engine failure can never fail boot.
            try {
                com.assistant.recovery.AdapterRecoveryEngine.start(this)
                logSafe("App.onCreate: AdapterRecoveryEngine started (main process)", "BOOT")
            } catch (t: Throwable) {
                faultSafe("AdapterRecoveryEngine.start", "${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
            // ComplianceMonitor wire (Ed's call, 2026-11-20): start the
            // 30 s COMPLIANCE heartbeat here, in the main process only,
            // next to FilterEngine.start() / AdapterRecoveryEngine.start().
            // App.onCreate runs on every process start, so the monitor
            // must NOT start in :survival (it would log a second
            // heartbeat from the headless process). The monitor only
            // LOGS (ENGINE READY / BLOCKED: <permission>) — it never
            // fixes, re-requests or changes anything. start() is
            // idempotent (started flag) and self-schedules its 30 s
            // re-post; the try/catch mirrors the blocks above so a
            // monitor failure can never fail boot.
            try {
                com.assistant.compliance.ComplianceMonitor.start(this)
                logSafe("App.onCreate: ComplianceMonitor started (main process)", "BOOT")
            } catch (t: Throwable) {
                faultSafe("ComplianceMonitor.start", "${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
            // SPLENDOR_P7_APP_START_END

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
            // SPLENDOR_P12_B8_SURVIVAL: DeathWatch was armed in the main
            // process only, so a :survival-only LMK kill was invisible
            // (no marker, no heartbeat, no death report). Markers are
            // per-process files, so arming here cannot collide with main.
            DeathWatch.install(this)
            logSafe("App.onCreate: Headless process initialized [$currentProcess]", "BOOT")
        }
    }
}
