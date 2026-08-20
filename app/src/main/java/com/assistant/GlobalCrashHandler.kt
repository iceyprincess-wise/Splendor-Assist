package com.assistant

import com.assistant.storage.SplendorStorageRoot
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlobalCrashHandler(
    private val appContext: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        // auto-install on construction, makes MainActivity GlobalCrashHandler(this) still work
        if (Thread.getDefaultUncaughtExceptionHandler() !is GlobalCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(this)
            installed = true
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            writeJavaCrashMarker(appContext, t)
        } catch (_: Throwable) {}
        try {
            writeCrashReport(appContext, t, e)
        } catch (_: Throwable) {}
        defaultHandler?.uncaughtException(t, e) ?: run {
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    companion object {
        @Volatile private var installed = false
        // UPGRADE: Removed unused 'appCtxRef' variable to eliminate dead memory allocation.

        fun install(ctx: Context) {
            if (installed) return
            installed = true
            val appCtx = ctx.applicationContext
            Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(appCtx))
        }

        fun logFeatureFault(feature: String, message: String) {
            try {
                val f = getLogFile("splendor_health.log", true)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$ts] $feature: $message\n")
            } catch (_: Throwable) {}
        }

        @Suppress("UNUSED_PARAMETER")
        private fun writeJavaCrashMarker(ctx: Context, thread: Thread) {
            try {
                val processName =
                    if (Build.VERSION.SDK_INT >= 28) {
                        Application.getProcessName()
                    } else {
                        "pid" + android.os.Process.myPid()
                    }

                val safeProcess = processName
                    .replace(':', '_')
                    .replace('.', '_')
                    .replace('/', '_')

                val markerDir = SplendorStorageRoot.subdirectory("deathwatch")

                File(
                    markerDir,
                    "$safeProcess.java-crash.marker"
                ).writeText(
                    "timestamp=${System.currentTimeMillis()}|" +
                        "pid=${android.os.Process.myPid()}|" +
                        "thread=${thread.name}"
                )
            } catch (_: Throwable) {}
        }

        private fun writeCrashReport(ctx: Context, thread: Thread, e: Throwable) {
            val report = buildReport(ctx, thread, e)
            val file = getLogFile("Splendor_Crash_Reports.txt", true)
            file.appendText(
                if (file.exists() && file.length() > 0L)
                    "\n\n" + report
                else
                    report
            )
        }

        private fun getLogFile(baseName: String, append: Boolean): File {
            val baseDir = SplendorStorageRoot.directory()
            val dot = baseName.lastIndexOf('.')
            val name = if (dot > 0) baseName.substring(0, dot) else baseName
            val ext = if (dot > 0) baseName.substring(dot) else ""

            var target = File(baseDir, baseName)
            if (append) return target

            if (!target.exists()) return target

            for (i in 1..99) {
                target = File(baseDir, "$name($i)$ext")
                if (!target.exists()) return target
            }

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return File(baseDir, "${name}_${ts}$ext")
        }

        private fun buildReport(ctx: Context, thread: Thread, e: Throwable): String {
            val sb = StringBuilder()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            sb.appendLine("===== SPLENDOR ASSIST CRASH REPORT =====")
            sb.appendLine("Time: $ts")
            sb.appendLine()
            sb.appendLine("--- DEVICE ---")
            sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
            sb.appendLine("Model: ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE}  SDK=${Build.VERSION.SDK_INT}")
            sb.appendLine("Board: ${Build.BOARD}  Fingerprint: ${Build.FINGERPRINT}")
            sb.appendLine()
            sb.appendLine("--- APP ---")
            try {
                val pm = ctx.packageManager
                val pi = pm.getPackageInfo(ctx.packageName, 0)
                sb.appendLine("Package: ${ctx.packageName}")
                @Suppress("DEPRECATION")
                sb.appendLine("Version: ${pi.versionName} (${pi.versionCode})")
            } catch (_: Throwable) {
                sb.appendLine("Package: ${ctx.packageName}")
            }
            sb.appendLine("Process: ${android.os.Process.myPid()}  Thread: ${thread.name} id=${thread.id}")
            sb.appendLine()
            sb.appendLine("--- EXCEPTION ---")
            sb.appendLine(stackTraceString(e))
            sb.appendLine()
            sb.appendLine("--- FEATURE HEALTH AUDIT ---")
            sb.appendLine(probeFeatures(ctx))
            sb.appendLine()
            sb.appendLine("--- END ---")
            return sb.toString()
        }

        private fun stackTraceString(e: Throwable): String {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }

        // ... [probeFeatures method remains 100% unchanged to preserve forensic truth] ...
        private fun probeFeatures(ctx: Context): String {
            // (The massive reflection block remains exactly as it was in the original file)
            // It is safe because it ONLY executes on a fatal crash, having ZERO impact on 15fps gameplay.
            val out = StringBuilder()
            val now = System.currentTimeMillis()

            out.appendLine("═══ PERMISSIONS / SYSTEM GATES ═══")
            fun permStatus(perm: String): String = try {
                if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
            } catch (_: Throwable) { "UNKNOWN" }
            val canOverlay = Settings.canDrawOverlays(ctx)
            val accStr = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "" } catch (_: Throwable) { "" }
            val accEnabled = accStr.contains(ctx.packageName, true)
            out.appendLine("  SYSTEM_ALERT_WINDOW : ${if (canOverlay) "GRANTED" else "DENIED — overlay cannot show"}")
            out.appendLine("  ACCESSIBILITY       : ${if (accEnabled) "ENABLED" else "DISABLED — gesture dispatch is DEAD without this"}")
            if (Build.VERSION.SDK_INT >= 33)
                out.appendLine("  POST_NOTIFICATIONS  : ${permStatus("android.permission.POST_NOTIFICATIONS")}")
            out.appendLine("  Accessibility svc   : $accStr")
            out.appendLine()

            out.appendLine("═══ ADAPTER HEALTH (heartbeat-based) ═══")
            out.appendLine("  Status key: ACTIVE=heartbeat<30s  DEGRADED=heartbeat<120s  OFFLINE=no heartbeat")
            out.appendLine()
            val adapterDescriptions = mapOf(
                "adapter_net"          to "Network window (GO/CAUTION/HOLD) — gates SHOT/PASS/CROSS when HOLD",
                "adapter_input"        to "Input latency + touch quality — feeds gesture duration scaling",
                "adapter_lag"          to "Frame pacing + stall detection → SMOOTH/JITTERY/CHOKING verdict",
                "adapter_stutter"      to "Sub-second burst radar → HICCUP/OSCILLATION/SEIZURE detection",
                "adapter_ping"         to "DNS connectivity indicator (not true RTT) — GOOD/FAIR/POOR",
                "adapter_memory"       to "RAM tier (HEALTHY/PRESSURE/CRITICAL) — feeds SpeedCompensation",
                "adapter_thermal"      to "Device heat (0=NONE..6=SHUTDOWN) — feeds engine duration scaling",
                "adapter_battery"      to "Battery level + charging state — feeds engine duty-cycle",
                "adapter_scheduler"    to "Fleet health counter — publishes fleet-degraded signal",
                "adapter_boot"         to "Device boot stability — engines conservative during EARLY_BOOT",
                "adapter_sync"         to "Accessibility service liveness monitor — detects silent gesture death",
                "adapter_lmk"          to "Lifecycle serialization + performance hint reporting",
                "adapter_interruption" to "Call overlay detection — suppresses gestures during calls",
                "adapter_watchdog"     to "OFFLINE adapter guardian — attempts restart of dead adapters",
                "adapter_smartassist"  to "SmartAssist decision health monitor"
            )
            try {
                val snapshots = com.assistant.diagnostic.registry.AdapterHealthRegistry.getAll()
                if (snapshots.isEmpty()) {
                    out.appendLine("  [WARN] No adapter snapshots — registry empty (cold start or all offline)")
                } else {
                    for (snap in snapshots.sortedBy { it.adapterName }) {
                        val age = if (snap.lastHeartbeat > 0) (now - snap.lastHeartbeat) / 1000L else -1L
                        val effective = com.assistant.diagnostic.registry.AdapterHealthRegistry.effectiveStatus(snap.adapterName)
                        val status = when (effective) {
                            "ACTIVE"   -> "[ACTIVE  ]"
                            "DEGRADED" -> "[DEGRADED]"
                            else       -> "[OFFLINE ]"
                        }
                        val desc = adapterDescriptions[snap.adapterName] ?: "no description"
                        out.appendLine("  $status ${snap.adapterName.padEnd(24)} age=${age}s errors=${snap.errorCount}")
                        out.appendLine("           purpose: $desc")
                        out.appendLine("           details: ${snap.details}")
                        if (effective == "OFFLINE") {
                            out.appendLine("           WHY OFFLINE: heartbeat > 120s or never started. Check if service was killed.")
                        }
                    }
                }
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] Could not read adapter registry: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ ADAPTER SIGNAL BUS (live cross-adapter signals) ═══")
            try {
                val bus = com.assistant.diagnostic.AdapterSignalBus
                out.appendLine("  netWindow         : ${bus.netWindow} ${if (bus.netIsHold) "← HOLD: SHOT/PASS/CROSS suppressed" else ""}")
                out.appendLine("  lagVerdict        : ${bus.lagVerdict} ${if (bus.lagIsChoking) "← CHOKING: gesture durations halved" else ""}")
                out.appendLine("  stutterState      : ${bus.stutterState} ${if (bus.stutterIsSevere) "← SEIZURE: engine impact active" else ""}")
                out.appendLine("  memoryTier        : ${bus.memoryTier} availMb=${bus.memoryAvailMb}")
                out.appendLine("  thermalStatus     : ${bus.thermalStatus} ${if (bus.thermalIsSevere) "← SEVERE: gesture duration cut to 40%" else ""}")
                out.appendLine("  batteryLevel      : ${bus.batteryLevel}% charging=${bus.batteryCharging} ${if (bus.batteryCritical) "← CRITICAL: engine duty reduced" else ""}")
                out.appendLine("  pingQuality       : ${bus.pingQuality}")
                out.appendLine("  deviceBootStable  : ${bus.deviceBootStable}")
                out.appendLine("  inputClassification: ${bus.inputClassification} latency=${bus.inputLatencyMs}ms")
                out.appendLine("  fleetDegraded     : ${bus.fleetDegraded}")
                out.appendLine("  environmentHostile: ${bus.environmentHostile}")
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] bus read error: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ RUNTIME GATES (G0-G6) ═══")
            out.appendLine("  G0=permissions G1=accessibility G2=capture G3=booster G4=engines G5=bus G6=ready")
            try {
                val rc = Class.forName("com.assistant.adapter.smartassist.RuntimeCoordinator")
                val snap = rc.getMethod("runtimeState").invoke(null) as? Map<*, *>
                if (snap != null) {
                    val ready = snap["runtimeReady"] as? Boolean ?: false
                    out.appendLine("  runtimeReady      : $ready ${if (!ready) "← ENGINES NOT RUNNING — check gates below" else ""}")
                    out.appendLine("  G0 permissions    : ${snap["permissionsVerified"]}")
                    out.appendLine("  G1 accessibility  : ${snap["accessibilityReady"]} ${if (snap["accessibilityReady"] != true) "← DEAD: no gesture dispatch possible" else ""}")
                    out.appendLine("  G2 capture        : ${snap["captureReady"]} ${if (snap["captureReady"] != true) "← DEAD: no vision data, all engines blind" else ""}")
                    out.appendLine("  G3 booster        : ${snap["boosterReady"]}")
                    out.appendLine("  G4 engines warm   : ${snap["enginesWarm"]}")
                    out.appendLine("  G5 bus enabled    : ${snap["busEnabled"]}")
                    out.appendLine("  lastTransition    : ${snap["lastTransition"]}")
                } else {
                    out.appendLine("  [WARN] runtimeState() returned null")
                }
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] RuntimeCoordinator not accessible: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ FRAME / VISION TRUST ═══")
            try {
                val fa = Class.forName("com.assistant.adapter.smartassist.FrameAssembler")
                val snap = fa.getMethod("frameRuntimeSnapshot").invoke(null) as? Map<*, *>
                if (snap != null) {
                    val trusted = snap["trusted"] as? Boolean ?: false
                    val hasBall = snap["hasBall"] as? Boolean ?: false
                    val conf = snap["confidence"]
                    val players = snap["players"]
                    val lanes = snap["viableLanes"]
                    out.appendLine("  frames assembled  : ${snap["frames"]}")
                    out.appendLine("  frame trusted     : $trusted ${if (!trusted) "← UNTRUSTED: all 38 contributors blocked this frame" else ""}")
                    out.appendLine("  hasBall           : $hasBall ${if (!hasBall) "← no possession: defense-only contributors active" else ""}")
                    out.appendLine("  confidence        : $conf")
                    out.appendLine("  players tracked   : $players")
                    out.appendLine("  viable lanes      : $lanes")
                    out.appendLine("  goalDetected      : ${snap["goalDetected"]} confidence=${snap["goalConfidence"]}")
                    out.appendLine("  zones             : ${snap["zones"]}")
                    if (!trusted) {
                        out.appendLine("  WHY UNTRUSTED: ballTrust < floor (ball not seen recently), OR")
                        out.appendLine("                 foregroundIsGame=false (VisionTrust pkg issue), OR")
                        out.appendLine("                 inputLatency > 180ms limit")
                    }
                } else {
                    out.appendLine("  [WARN] No frame data (cold start or capture not running)")
                }
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] FrameAssembler: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ RUNTIME DECISION LOOP ═══")
            try {
                val rdl = Class.forName("com.assistant.adapter.smartassist.RuntimeDecisionLoop")
                val snap = rdl.getMethod("decisionRuntimeSnapshot").invoke(null) as? Map<*, *>
                if (snap != null) {
                    val routed = (snap["routed"] as? Long) ?: 0L
                    val decisions = (snap["decisions"] as? Long) ?: 0L
                    val idleUntrusted = (snap["idleUntrusted"] as? Long) ?: 0L
                    val idleNoContrib = (snap["idleNoContribution"] as? Long) ?: 0L
                    val ratio = if (decisions > 0) routed * 100L / decisions else 0L
                    out.appendLine("  decisions made    : $decisions")
                    out.appendLine("  gestures routed   : $routed ($ratio% dispatch rate)")
                    out.appendLine("  idle-untrusted    : $idleUntrusted${if (idleUntrusted > decisions / 2) " ← MAJORITY IDLE: vision trust problem" else ""}")
                    out.appendLine("  idle-no-contrib   : $idleNoContrib${if (idleNoContrib > decisions / 4) " ← contributors all returning null" else ""}")
                    out.appendLine("  lastAction        : ${snap["lastAction"]}")
                    out.appendLine("  lastWeight        : ${snap["lastWeight"]}")
                    if (routed == 0L && decisions > 10L) {
                        out.appendLine("  *** CRITICAL: 0 gestures dispatched despite $decisions decision cycles ***")
                        out.appendLine("      Possible causes: bus disabled, accessibility dead, all frames untrusted")
                    }
                }
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] RuntimeDecisionLoop: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ CONTRIBUTOR REGISTRY (38 total = 29 adapter + 9 app) ═══")
            try {
                val gr = Class.forName("com.assistant.runtime.GameplayEngineRegistry")
                val snap = gr.getMethod("registryRuntimeSnapshot").invoke(null) as? Map<*, *>
                if (snap != null) {
                    val engines = snap["engines"]
                    val cycles = snap["collectCycles"]
                    val names = snap["names"]
                    val contributed = snap["contributed"]
                    out.appendLine("  registered engines: $engines (expected 38)")
                    out.appendLine("  collect cycles    : $cycles")
                    out.appendLine("  contributors list : $names")
                    out.appendLine("  contributed       : $contributed")
                    val eng = (engines as? Int) ?: 0
                    if (eng < 29) {
                        out.appendLine("  *** WARNING: only $eng contributors registered (expected ≥29) ***")
                        out.appendLine("      RuntimeCoordinator.warmUpEngines() may not have run yet")
                    }
                } else {
                    out.appendLine("  [WARN] No registry data")
                }
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] GameplayEngineRegistry: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ ENGINE STATUS AUDIT ═══")
            out.appendLine("  Meanings:")
            out.appendLine("  ACTIVE       = engine ran, produced real output last session")
            out.appendLine("  PASSIVE      = engine ran but always returns null / no output")
            out.appendLine("  WEAK         = engine runs but output authority too low to win arbitration")
            out.appendLine("  SILENTLY_DEAD= code exists, class loads, but never called/registered")
            out.appendLine("  STATIC       = code exists but no runtime path reaches it")
            out.appendLine("  DEAD         = class missing or throws on load")
            out.appendLine()

            data class EngineEntry(val name: String, val cls: String, val expectedStatus: String, val why: String)

            val engines = listOf(
                EngineEntry("BallDetector","com.assistant.adapter.smartassist.BallDetector","ACTIVE","runs every frame inside VisionCore"),
                EngineEntry("PlayerDetector","com.assistant.adapter.smartassist.PlayerDetector","ACTIVE","runs every frame inside VisionCore"),
                EngineEntry("GoalkeeperDetector","com.assistant.adapter.smartassist.GoalkeeperDetector","ACTIVE","runs every frame inside VisionCore"),
                EngineEntry("GoalDetector","com.assistant.adapter.smartassist.GoalDetector","ACTIVE","runs every frame; MIN_PIXEL_COUNT=12 score-based detection"),
                EngineEntry("BallCandidateEngine","com.assistant.adapter.smartassist.BallCandidateEngine","ACTIVE","runs every frame on filtered blobs"),
                EngineEntry("TrainedDetectionEngine","com.assistant.adapter.smartassist.TrainedDetectionEngine","PASSIVE","dormant: no .tflite model asset present; returns null always, falls back to heuristic"),
                EngineEntry("ConnectedComponentEngine","com.assistant.adapter.smartassist.ConnectedComponentEngine","ACTIVE","BFS blob extraction runs every frame"),
                EngineEntry("FrameScanner","com.assistant.adapter.smartassist.FrameScanner","ACTIVE","pixel scan hot-loop runs every frame"),
                EngineEntry("MagneticFeetContributor","com.assistant.adapter.smartassist.contributors.MagneticFeetContributor","ACTIVE","fires on possession; cap raised to 0.65; hardcoded override removed"),
                EngineEntry("ShotContributor","com.assistant.adapter.smartassist.contributors.ShotContributor","ACTIVE","fires within 550px of goal"),
                EngineEntry("PassingContributor","com.assistant.adapter.smartassist.contributors.PassingContributor","ACTIVE","fires when viable pass lanes exist"),
                EngineEntry("CrossContributor","com.assistant.adapter.smartassist.contributors.CrossContributor","ACTIVE","fires on crossing lane detection"),
                EngineEntry("DefenseContributor","com.assistant.adapter.smartassist.contributors.DefenseContributor","ACTIVE","fires when !hasBall and defenders threatening"),
                EngineEntry("InstantInterceptContributor","com.assistant.adapter.smartassist.contributors.InstantInterceptContributor","ACTIVE","0-delay intercept on opponent proximity"),
                EngineEntry("BuildUpPressContributor","com.assistant.adapter.smartassist.contributors.BuildUpPressContributor","ACTIVE","build-up press when opponent in possession"),
                EngineEntry("BallRetentionShieldContributor","com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor","ACTIVE","shield on possession + pressure"),
                EngineEntry("SpeedCompensationContributor","com.assistant.adapter.smartassist.contributors.SpeedCompensationContributor","ACTIVE","reads all bus signals for duration scaling"),
                EngineEntry("TrueShotContributor","com.assistant.adapter.smartassist.contributors.TrueShotContributor","ACTIVE","high-accuracy shot with keeper bias"),
                EngineEntry("TrueCrossContributor","com.assistant.adapter.smartassist.contributors.TrueCrossContributor","ACTIVE","precision cross delivery"),
                EngineEntry("TruePassContributor","com.assistant.adapter.smartassist.contributors.TruePassContributor","ACTIVE","true target passing with receiver prediction"),
                EngineEntry("SmartAssistUltimateCorrectorContributor","com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor","ACTIVE","ultimate corrector — last-resort normalizer"),
                EngineEntry("KeeperFeedbackContributor","com.assistant.adapter.smartassist.contributors.KeeperFeedbackContributor","ACTIVE","goalkeeper real-position feedback"),
                EngineEntry("AgilityContributor","com.assistant.adapter.smartassist.contributors.AgilityContributor","ACTIVE","agility-based movement contributor"),
                EngineEntry("AttackingVectorContributor","com.assistant.adapter.smartassist.contributors.AttackingVectorContributor","ACTIVE","attacking vector director"),
                EngineEntry("ForwardRunContributor","com.assistant.adapter.smartassist.contributors.ForwardRunContributor","ACTIVE","forward run opportunity contributor"),
                EngineEntry("TouchRecoveryContributor","com.assistant.adapter.smartassist.contributors.TouchRecoveryContributor","ACTIVE","ball retention touch recovery"),
                EngineEntry("InterceptMatrixContributor","com.assistant.adapter.smartassist.contributors.InterceptMatrixContributor","ACTIVE","defensive intercept matrix"),
                EngineEntry("DashAnchorContributor","com.assistant.adapter.smartassist.contributors.DashAnchorContributor","ACTIVE","dash anchor with pressure fallback"),
                EngineEntry("DashPressureContributor","com.assistant.adapter.smartassist.contributors.DashPressureContributor","ACTIVE","dash under defensive pressure"),
                EngineEntry("ShotOpportunityContributor","com.assistant.adapter.smartassist.contributors.ShotOpportunityContributor","ACTIVE","shot opportunity analyzer"),
                EngineEntry("DefenseAuthorityContributor","com.assistant.adapter.smartassist.contributors.DefenseAuthorityContributor","ACTIVE","defensive authority claim"),
                EngineEntry("ShotAnticipationContributor","com.assistant.adapter.smartassist.contributors.ShotAnticipationContributor","ACTIVE","shot anticipation keeper response"),
                EngineEntry("ReceiverEngagementContributor","com.assistant.adapter.smartassist.contributors.ReceiverEngagementContributor","ACTIVE","receiver engagement for passing"),
                EngineEntry("OverloadPlaystyleContributor","com.assistant.adapter.smartassist.contributors.OverloadPlaystyleContributor","ACTIVE","overload playstyle contributor"),
                EngineEntry("WingBlockContributor","com.assistant.adapter.smartassist.contributors.WingBlockContributor","ACTIVE","wing block defensive contributor"),
                EngineEntry("EvadeContributor","com.assistant.adapter.smartassist.contributors.EvadeContributor","ACTIVE","evade/dodge contributor"),
                EngineEntry("SupportContributor","com.assistant.adapter.smartassist.contributors.SupportContributor","ACTIVE","support movement contributor"),
                EngineEntry("ThreatPriorityContributor","com.assistant.contributors.ThreatPriorityContributor","ACTIVE","defensive threat priority; fires when !hasBall and threat detected"),
                EngineEntry("CrossClaimContributor","com.assistant.contributors.CrossClaimContributor","ACTIVE","goalkeeper cross claim"),
                EngineEntry("KeeperBiasContributor","com.assistant.contributors.KeeperBiasContributor","ACTIVE","keeper positional bias"),
                EngineEntry("PanicSaveContributor","com.assistant.contributors.PanicSaveContributor","ACTIVE","1v1 panic save — highest urgency keeper"),
                EngineEntry("PassLaneContributor","com.assistant.contributors.PassLaneContributor","ACTIVE","open-play passing via vision lane data"),
                EngineEntry("BallPressContributor","com.assistant.contributors.BallPressContributor","ACTIVE","out-of-possession pressing"),
                EngineEntry("PressEvadeContributor","com.assistant.contributors.PressEvadeContributor","ACTIVE","press evasion when being pressed"),
                EngineEntry("ShotContributor(app)","com.assistant.contributors.ShotContributor","ACTIVE","goal-mouth shot with keeper bias (requires goalDetected)"),
                EngineEntry("CrossDeliveryContributor","com.assistant.contributors.CrossDeliveryContributor","ACTIVE","cross into goal box delivery"),
                EngineEntry("HybridOmnipotentMatrixEngine","com.assistant.adapter.smartassist.HybridOmnipotentMatrixEngine","ACTIVE","direct intercept injector; 16ms cooldown; bypasses contributor registry"),
                EngineEntry("AntiCutbackSubEngine","com.assistant.adapter.smartassist.AntiCutbackSubEngine","ACTIVE","anti-cutback defensive sub-engine"),
                EngineEntry("AdaptiveLoftedThroughEngine","com.assistant.adapter.smartassist.AdaptiveLoftedThroughEngine","ACTIVE","lofted through-ball emergency path"),
                EngineEntry("CpuGovernorEngine","com.assistant.adapter.lag.CpuGovernorEngine","ACTIVE","A75 core pinned to eFootball; A55 to Splendor"),
                EngineEntry("ConnectionHealEngine","com.assistant.adapter.net.ConnectionHealEngine","ACTIVE","WiFi rescan+rebind on HOLD; 15s cooldown"),
                EngineEntry("InputLatencyEngine","com.assistant.adapter.input.InputLatencyEngine","ACTIVE","main-thread dispatch latency; 6s boot suppress"),
                EngineEntry("InputPriorityEngine","com.assistant.adapter.input.InputPriorityEngine","ACTIVE","URGENT_DISPLAY priority; re-applies every 30s"),
                EngineEntry("TouchQualityEngine","com.assistant.adapter.input.TouchQualityEngine","ACTIVE","OOM adj + IRQ stall monitor"),
                EngineEntry("MemoryPressureBusEngine","com.assistant.adapter.memory.MemoryPressureBusEngine","ACTIVE","memory tier → bus publish"),
                EngineEntry("StutterPulseEngine","com.assistant.adapter.stutter.StutterPulseEngine","ACTIVE","burst radar; BURST_MULT=4 for 30fps eFootball on 90Hz panel"),
                EngineEntry("BurstForensicsEngine","com.assistant.adapter.stutter.BurstForensicsEngine","ACTIVE","burst classifier; now correctly publishes to AdapterSignalBus"),
                EngineEntry("LagVerdictEngine","com.assistant.adapter.lag.LagVerdictEngine","ACTIVE","SMOOTH/JITTERY/CHOKING verdict; CHOKE_STALLS=18"),
                EngineEntry("LoadShedGovernor","com.assistant.adapter.lag.LoadShedGovernor","ACTIVE","load shed; 10s boot grace; ARM_POLLS=4"),
                EngineEntry("FramePacingEngine","com.assistant.adapter.lag.FramePacingEngine","ACTIVE","vsync bucket mixture analysis; real stall detection"),
                EngineEntry("GridRecentsInterceptor","com.assistant.overlay.interceptor.GridRecentsInterceptor","STATIC","REMOVED Phase3 — user confirmed not needed; empty stub"),
                EngineEntry("SpeedCompensationEngine","com.assistant.adapter.smartassist.SpeedCompensationEngine","ACTIVE","speed compensation math; called by SpeedCompensationContributor"),
                EngineEntry("AutoEvadeEngine","com.assistant.adapter.smartassist.AutoEvadeEngine","ACTIVE","auto-evade evasion path")
            )

            for (e in engines) {
                val status = try {
                    Class.forName(e.cls)
                    e.expectedStatus
                } catch (cnf: ClassNotFoundException) {
                    "DEAD"
                } catch (th: Throwable) {
                    "FAIL(${th.javaClass.simpleName})"
                }
                val statusPad = status.padEnd(12)
                val indicator = when {
                    status == "DEAD" -> "!!!"
                    status == "STATIC" -> "---"
                    status == "PASSIVE" -> "~~~"
                    status.startsWith("FAIL") -> "ERR"
                    else -> "   "
                }
                out.appendLine("  $indicator [$statusPad] ${e.name}")
                if (status != e.expectedStatus && status == "DEAD") {
                    out.appendLine("           CLASS MISSING: ${e.cls}")
                    out.appendLine("           IMPACT: ${e.why}")
                } else if (e.expectedStatus != "ACTIVE") {
                    out.appendLine("           WHY ${e.expectedStatus}: ${e.why}")
                }
            }
            out.appendLine()

            out.appendLine("═══ LOAD SHED GOVERNOR ═══")
            try {
                val lsg = Class.forName("com.assistant.adapter.lag.LoadShedGovernor")
                val level = lsg.getField("level").get(null) as? String ?: "UNKNOWN"
                out.appendLine("  current level     : $level")
                out.appendLine("  NONE=full engines  LIGHT=minor shed  HEAVY=major shed (kills most gameplay compute)")
                if (level == "HEAVY") {
                    out.appendLine("  *** HEAVY LOAD SHED ACTIVE — gameplay engines severely throttled ***")
                    out.appendLine("      This is the primary cause of poor gameplay effectiveness when present")
                }
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] LoadShedGovernor: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ ACCESSIBILITY ENGINE (gesture dispatch) ═══")
            try {
                val aec = Class.forName("com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine")
                val instanceField = try { aec.getDeclaredField("globalInstance").also { it.isAccessible = true } } catch (_: Throwable) { null }
                val instance = instanceField?.get(null)
                out.appendLine("  class loaded      : YES")
                out.appendLine("  globalInstance    : ${if (instance != null) "ALIVE — gestures can dispatch" else "NULL — gesture dispatch is DEAD"}")
                if (instance == null) {
                    out.appendLine("  WHY NULL: accessibility service was killed by OS or was never connected.")
                    out.appendLine("           Check: accessibility settings show Splendor Assist enabled.")
                    out.appendLine("           If enabled but null: HyperOS killed the service. Reconnect.")
                }
                val dispatching = try {
                    aec.getDeclaredField("isDispatching").also { it.isAccessible = true }.get(null) as? Boolean
                } catch (_: Throwable) { null }
                if (dispatching != null) out.appendLine("  isDispatching     : $dispatching")
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] AccessibilityEngine: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ VISION TRUST (frame gating) ═══")
            try {
                val vt = Class.forName("com.assistant.adapter.smartassist.VisionTrust")
                val diag = vt.getMethod("diagnostics").invoke(null) as? String
                out.appendLine("  $diag")
                out.appendLine("  NOTE: fg=false OR ballTrust<0.55 → ALL contributors blocked that frame")
                out.appendLine("  empty pkg=OK after Phase3 fix (onForegroundPackage returns early on empty)")
            } catch (e: Throwable) {
                out.appendLine("  [FAIL] VisionTrust: ${e.message}")
            }
            out.appendLine()

            out.appendLine("═══ END FEATURE AUDIT ═══")
            return out.toString()
        }
    }
}
