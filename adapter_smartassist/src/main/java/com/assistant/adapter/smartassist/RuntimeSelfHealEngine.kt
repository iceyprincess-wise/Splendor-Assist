package com.assistant.adapter.smartassist

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PHASE 4 — RuntimeSelfHealEngine (AI Self-Heal Agent)
 *
 * The global crash report shows what is wrong AFTER a crash.
 * This engine shows what goes wrong DURING normal gameplay — silently,
 * without crashing, while the user is watching their engines "work" but
 * producing no gestures.
 *
 * Architecture:
 *   - Daemon thread, 5s check cycle, starts at G6 (RUNTIME_READY)
 *   - In-memory event list (last 100 events) for AgentRoomActivity UI
 *   - File log: [externalFilesDir]/Splendor_HealLog.txt
 *
 * Detects and fixes (in-memory, no code change, no restart):
 *   1. VisionTrust.foregroundIsGame = false while capture is active
 *      Cause: eFootball child surfaces send empty pkg → VisionTrust returns early
 *      Fix:   VisionTrust.setGameForeground(true) — all contributors unblocked
 *
 *   2. AdapterSignalBus signals stuck at UNKNOWN > 30s after runtime ready
 *      Cause: adapter services not started or bus publish never called
 *      Fix:   Publish safe defaults (GO/SMOOTH/CALM/HEALTHY) so contributors
 *             are never silently blocked by UNKNOWN state
 *
 *   3. Load shed HEAVY > 20s — logs exact cause (lag verdict + stutter state)
 *      Cannot force-release (controlled by LagVerdictEngine), but log gives
 *      the exact admin setting to raise
 *
 *   4. Frame capture stale > 5s — ImageReader capture thread has died
 *      Cannot fix — requires app restart. Logs clearly with instruction.
 *
 *   5. Zero dispatch rate while decisions cycle
 *      Detects whether cause is: vision trust (idle-untrusted dominant) OR
 *      contributors all null (idle-no-contribution dominant)
 *      Fix for vision trust case: force foregroundIsGame=true
 *
 *   6. Contributor registry count < 29
 *      Logs gap — warmUpEngines() may not have completed
 */
object RuntimeSelfHealEngine {

    data class HealEvent(
        val timestamp: String,
        val category: String,
        val detected: String,
        val fix: String,
        val severity: String   // FIXED / CRITICAL / WARNING / INFO
    )

    val healEvents: CopyOnWriteArrayList<HealEvent> = CopyOnWriteArrayList()

    @Volatile var agentStatus: String = "IDLE"
        private set
    @Volatile var totalHeals: Int = 0
        private set

    @Volatile private var running = false
    @Volatile private var runtimeStartedMs: Long = 0L
    private var contextRef: WeakReference<android.content.Context>? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(ctx: android.content.Context) {
        contextRef = WeakReference(ctx.applicationContext)
    }

    fun start() {
        if (running) return
        running = true
        runtimeStartedMs = System.currentTimeMillis()
        agentStatus = "STARTING"
        val t = Thread {
            // 10s grace — let everything boot and warm up before first check
            try { Thread.sleep(10_000L) } catch (_: Throwable) { return@Thread }
            agentStatus = "MONITORING"
            RuntimeLogger.log("AI Self-Heal Agent: MONITORING ACTIVE (5s cycle)", "AGENT")
            while (running) {
                try {
                    runChecks()
                } catch (e: Throwable) {
                    try { RuntimeLogger.log("AGENT FAULT: ${e.javaClass.simpleName}: ${e.message}", "AGENT") } catch (_: Throwable) {}
                }
                try { Thread.sleep(5_000L) } catch (_: Throwable) { return@Thread }
            }
            agentStatus = "STOPPED"
        }
        t.isDaemon = true
        t.name = "splendor-self-heal-agent"
        t.start()
        RuntimeLogger.log("RuntimeSelfHealEngine started — AI agent daemon online", "AGENT")
    }

    fun stop() { running = false; agentStatus = "IDLE" }

    private fun runChecks() {
        val runtimeAgeMs = System.currentTimeMillis() - runtimeStartedMs
        // Only flag issues after 15s warmup — suppress boot-time transients
        val warmed = runtimeAgeMs > 15_000L
        checkVisionTrust(warmed)
        checkBusSignals(warmed)
        checkLoadShed()
        checkCaptureStale()
        checkDispatchRate()
        checkContributorRegistry(warmed)
    }

    // ─────────────────────────────────────────────────────────────────────
    // CHECK 1: VisionTrust.foregroundIsGame
    // Most common cause of zero-gesture sessions in eFootball 2027:
    // child surfaces send empty packageName → onForegroundPackage returns early
    // → foregroundIsGame never set to true → all 38 contributors blocked forever
    // ─────────────────────────────────────────────────────────────────────
    private fun checkVisionTrust(warmed: Boolean) {
        if (!warmed) return
        try {
            val fg = VisionTrust.isGameForeground()
            if (!fg) {
                val lastFrame = FrameAssembler.current()
                val frameAgeMs = if (lastFrame != null)
                    System.currentTimeMillis() - lastFrame.timestampMs
                else Long.MAX_VALUE

                if (frameAgeMs < Long.MAX_VALUE && frameAgeMs < 3000L) {
                    // Frames ARE coming in (< 3s old) but foreground gate is blocking
                    // → This is the empty-pkg bug. Force the gate open.
                    VisionTrust.setGameForeground(true)
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "VISION_TRUST",
                        detected = "foregroundIsGame=false while capture active (frame ${frameAgeMs}ms old). Cause: eFootball child surface sends empty packageName → VisionTrust.onForegroundPackage returns early → gate never opens.",
                        fix = "Called VisionTrust.setGameForeground(true). All 38 contributors unblocked. Will persist until non-game package detected.",
                        severity = "FIXED"
                    ))
                    RuntimeLogger.log("AGENT HEAL #$totalHeals VISION_TRUST: fg forced true — empty-pkg bug", "AGENT")
                } else if (frameAgeMs != Long.MAX_VALUE) {
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "VISION_TRUST",
                        detected = "foregroundIsGame=false. Last frame ${frameAgeMs / 1000}s ago. Game may not be active or capture may be stale.",
                        fix = "MONITORING — if game is active and this persists, capture thread may have died.",
                        severity = "INFO"
                    ))
                }
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CHECK 2: Bus signals stuck at UNKNOWN
    // All bus signals default to "UNKNOWN". If adapters never publish,
    // SpeedCompensationContributor and RuntimeDecisionLoop read UNKNOWN
    // and behave as if no environmental data exists.
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var busUnknownSinceMs: Long = 0L

    private fun checkBusSignals(warmed: Boolean) {
        if (!warmed) return
        try {
            val bus = AdapterSignalBus
            val allUnknown = bus.netWindow == "UNKNOWN" && bus.lagVerdict == "UNKNOWN" &&
                    bus.stutterState == "UNKNOWN" && bus.memoryTier == "UNKNOWN"

            if (allUnknown) {
                if (busUnknownSinceMs == 0L) busUnknownSinceMs = System.currentTimeMillis()
                val stuckMs = System.currentTimeMillis() - busUnknownSinceMs
                if (stuckMs > 30_000L) {
                    // Publish safe defaults — contributors can now function normally
                    bus.publishNet("GO")
                    bus.publishLag("SMOOTH")
                    bus.publishStutter("CALM")
                    bus.publishMemory("HEALTHY", 1500L)
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "BUS_SIGNALS",
                        detected = "ALL bus signals stuck at UNKNOWN for ${stuckMs / 1000}s after warmup. Adapter services not publishing. SpeedCompensationContributor and RuntimeDecisionLoop blind.",
                        fix = "Published safe defaults: net=GO lag=SMOOTH stutter=CALM memory=HEALTHY/1500MB. All signal-dependent contributors now unblocked.",
                        severity = "FIXED"
                    ))
                    RuntimeLogger.log("AGENT HEAL #$totalHeals BUS: published safe defaults (all UNKNOWN for ${stuckMs / 1000}s)", "AGENT")
                    busUnknownSinceMs = 0L
                }
            } else {
                busUnknownSinceMs = 0L  // signals are flowing

                // Check for partial gaps — individual adapter dead
                val unknowns = buildList {
                    if (AdapterSignalBus.netWindow == "UNKNOWN") add("net(adapter_net dead?)")
                    if (AdapterSignalBus.lagVerdict == "UNKNOWN") add("lag(adapter_lag dead?)")
                    if (AdapterSignalBus.stutterState == "UNKNOWN") add("stutter(adapter_stutter dead?)")
                    if (AdapterSignalBus.memoryTier == "UNKNOWN") add("memory(adapter_memory dead?)")
                }
                if (unknowns.size in 1..2) {
                    // Only log partial gaps if persistent (not just slow adapter startup)
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "BUS_SIGNALS",
                        detected = "Partial bus gap: ${unknowns.joinToString(", ")} still UNKNOWN",
                        fix = "MONITORING — specific adapter(s) may be OFFLINE (WatchdogAdapter will attempt restart)",
                        severity = "WARNING"
                    ))
                }
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CHECK 3: LoadShed HEAVY
    // HEAVY shed kills most gameplay compute. Cannot force-release.
    // Log exact cause so user knows which admin value to raise.
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var heavySinceMs: Long = 0L
    @Volatile private var heavyLogged = false

    private fun checkLoadShed() {
        try {
            val shed = PerformanceTelemetryRegistry.currentLoadShed()
            val lag = AdapterSignalBus.lagVerdict
            val stutter = AdapterSignalBus.stutterState

            if (shed == "HEAVY") {
                if (heavySinceMs == 0L) { heavySinceMs = System.currentTimeMillis(); heavyLogged = false }
                val heavyMs = System.currentTimeMillis() - heavySinceMs
                if (heavyMs > 20_000L && !heavyLogged) {
                    heavyLogged = true
                    val cause = when {
                        stutter == "SEIZURE" -> "BurstForensicsEngine reported SEIZURE stutter → HEAVY armed immediately"
                        lag == "CHOKING" -> "LagVerdictEngine reported CHOKING → HEAVY armed after arm_polls=4 cycles"
                        else -> "lag=$lag stutter=$stutter (check LagVerdictEngine.verdict)"
                    }
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOAD_SHED",
                        detected = "LoadShedGovernor HEAVY for ${heavyMs / 1000}s. $cause. ALL gameplay engines severely throttled — this is why gestures feel absent.",
                        fix = "Cannot force-release (controlled by LagVerdictEngine). Admin fixes: raise lag.shed.arm_polls (default 4 → try 8) or raise stutter.forensics.seizure_ms (default 150 → try 200) in Admin Settings.",
                        severity = "CRITICAL"
                    ))
                    RuntimeLogger.log("AGENT CRITICAL LOAD_SHED: HEAVY ${heavyMs / 1000}s — cause: $cause", "AGENT")
                }
            } else {
                if (heavySinceMs > 0L) {
                    val wasHeavyMs = System.currentTimeMillis() - heavySinceMs
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOAD_SHED",
                        detected = "LoadShedGovernor returned to $shed from HEAVY (was heavy for ${wasHeavyMs / 1000}s)",
                        fix = "No fix needed — shed released naturally",
                        severity = "INFO"
                    ))
                }
                heavySinceMs = 0L; heavyLogged = false
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CHECK 4: Capture staleness
    // If FrameAssembler hasn't assembled a new frame in > 5s, the ImageReader
    // capture thread has died silently (OOM, exception escaped catch block, etc.)
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var lastKnownFrameId: Long = -1L
    @Volatile private var captureStaleLogged = false

    private fun checkCaptureStale() {
        try {
            val f = FrameAssembler.current() ?: return
            if (f.frameId > lastKnownFrameId) {
                lastKnownFrameId = f.frameId
                captureStaleLogged = false
                return  // frames advancing — OK
            }
            val staleMs = System.currentTimeMillis() - f.timestampMs
            if (staleMs > 5000L && !captureStaleLogged) {
                captureStaleLogged = true
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE",
                    detected = "Last assembled frame is ${staleMs / 1000}s old (frameId=${f.frameId}). ImageReader capture thread appears dead. No new vision data = all engine output stale.",
                    fix = "NONE — capture thread death requires full app restart. Force-stop Splendor Assist and reopen it.",
                    severity = "CRITICAL"
                ))
                RuntimeLogger.log("AGENT CRITICAL CAPTURE STALE: ${staleMs / 1000}s since last frame", "AGENT")
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CHECK 5: Zero dispatch rate
    // Decisions cycling but zero gestures dispatched = something upstream is blocked
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var prevDecisions: Long = 0L
    @Volatile private var prevRouted: Long = 0L
    @Volatile private var zeroDispatchSinceMs: Long = 0L

    private fun checkDispatchRate() {
        try {
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val decisions = (snap["decisions"] as? Long) ?: 0L
            val routed = (snap["routed"] as? Long) ?: 0L
            val idleUntrusted = (snap["idleUntrusted"] as? Long) ?: 0L
            val idleNoContrib = (snap["idleNoContribution"] as? Long) ?: 0L

            val newDecisions = decisions - prevDecisions
            val newRouted = routed - prevRouted

            if (newDecisions > 30L) {
                // Decisions are actively cycling this period
                if (newRouted == 0L) {
                    if (zeroDispatchSinceMs == 0L) zeroDispatchSinceMs = System.currentTimeMillis()
                    val zeroMs = System.currentTimeMillis() - zeroDispatchSinceMs
                    if (zeroMs > 10_000L) {
                        val trustPct = if (decisions > 0) idleUntrusted * 100L / decisions else 0L
                        val contribPct = if (decisions > 0) idleNoContrib * 100L / decisions else 0L
                        val reason = when {
                            idleUntrusted > idleNoContrib ->
                                "VISION_TRUST blocks ${trustPct}% of frames — VisionTrust.foregroundIsGame or ballTrust<0.55"
                            idleNoContrib > idleUntrusted ->
                                "CONTRIBUTORS all null for ${contribPct}% of frames — no eligible contributors winning arbitration"
                            else ->
                                "accessibility=null (check SmartAssistAccessibilityEngine.globalInstance)"
                        }
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "DISPATCH",
                            detected = "ZERO gestures dispatched over last ${zeroMs / 1000}s despite ${newDecisions} decision cycles. Reason: $reason. lastAction=${snap["lastAction"]}",
                            fix = if (idleUntrusted > idleNoContrib) "Applying VisionTrust.setGameForeground(true) — vision trust was blocking all frames" else "LOGGING — contributor arbitration issue requires analysis",
                            severity = "CRITICAL"
                        ))
                        RuntimeLogger.log("AGENT CRITICAL DISPATCH: 0 gestures/${zeroMs / 1000}s — $reason", "AGENT")

                        // Auto-fix: if vision trust is the dominant blocker, force it open
                        if (idleUntrusted > idleNoContrib) {
                            VisionTrust.setGameForeground(true)
                            totalHeals++
                            RuntimeLogger.log("AGENT HEAL #$totalHeals DISPATCH→VISION_TRUST: forced fg=true", "AGENT")
                        }
                    }
                } else {
                    zeroDispatchSinceMs = 0L  // gestures dispatching — good
                }
            } else if (newDecisions == 0L && decisions > 100L) {
                // Decision loop stopped entirely (runtime may have paused)
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "DISPATCH",
                    detected = "RuntimeDecisionLoop: decision counter frozen at $decisions (loop not running this period)",
                    fix = "MONITORING — loop may have paused due to capture stale or runtime shutdown",
                    severity = "WARNING"
                ))
            }

            prevDecisions = decisions
            prevRouted = routed
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CHECK 6: Contributor registry
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var registryGapLogged = false

    private fun checkContributorRegistry(warmed: Boolean) {
        if (!warmed) return
        try {
            val cls = Class.forName("com.assistant.runtime.GameplayEngineRegistry")
            @Suppress("UNCHECKED_CAST")
            val snap = cls.getMethod("registryRuntimeSnapshot").invoke(null) as? Map<String, Any> ?: return
            val count = (snap["engines"] as? Int) ?: return
            if (count < 29 && !registryGapLogged) {
                registryGapLogged = true
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "REGISTRY",
                    detected = "Only $count contributors registered (expected ≥29). Missing ${29 - count} contributors. Registered: ${snap["names"]}",
                    fix = "NONE — warmUpEngines() in RuntimeCoordinator must complete at G4. Likely cause: G4 never reached (accessibility or capture gate failed).",
                    severity = "CRITICAL"
                ))
                RuntimeLogger.log("AGENT CRITICAL REGISTRY: only $count/29 contributors registered", "AGENT")
            } else if (count >= 29) {
                registryGapLogged = false  // reset for next check
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // File + memory logging
    // ─────────────────────────────────────────────────────────────────────
    private fun record(ev: HealEvent) {
        healEvents.add(ev)
        while (healEvents.size > 100) try { healEvents.removeAt(0) } catch (_: Throwable) {}
        writeToFile(ev)
        try {
            RuntimeLogger.log("[${ev.severity}] ${ev.category}: ${ev.detected.take(120)}", "AGENT")
        } catch (_: Throwable) {}
    }

    private fun writeToFile(ev: HealEvent) {
        try {
            val ctx = contextRef?.get() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val file = File(dir, "Splendor_HealLog.txt")
            FileWriter(file, true).use { w ->
                w.write("[${ev.timestamp}] [${ev.severity}] [${ev.category}]\n")
                w.write("  DETECTED: ${ev.detected}\n")
                w.write("  FIX:      ${ev.fix}\n")
                w.write("\n")
            }
        } catch (_: Throwable) {}
    }

    fun getStatusSummary(): String {
        return try {
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val decisions = (snap["decisions"] as? Long) ?: 0L
            val routed = (snap["routed"] as? Long) ?: 0L
            val dispatchPct = if (decisions > 0) routed * 100L / decisions else 0L
            val shed = try { PerformanceTelemetryRegistry.currentLoadShed() } catch (_: Throwable) { "?" }
            val fg = try { VisionTrust.isGameForeground() } catch (_: Throwable) { false }
            buildString {
                appendLine("STATUS: $agentStatus  |  total heals: $totalHeals")
                appendLine("foreground=$fg  |  shed=$shed  |  dispatch=${dispatchPct}%")
                appendLine("net=${AdapterSignalBus.netWindow}  lag=${AdapterSignalBus.lagVerdict}")
                appendLine("stutter=${AdapterSignalBus.stutterState}  mem=${AdapterSignalBus.memoryTier}")
                appendLine("thermal=${AdapterSignalBus.thermalStatus}  battery=${AdapterSignalBus.batteryLevel}%")
                appendLine("decisions=$decisions  routed=$routed")
                appendLine("lastAction=${snap["lastAction"]}")
                appendLine("boot=${AdapterSignalBus.deviceBootStable}  fleet_degraded=${AdapterSignalBus.fleetDegraded}")
            }
        } catch (e: Throwable) { "AGENT STATUS READ ERROR: ${e.message}" }
    }
}
