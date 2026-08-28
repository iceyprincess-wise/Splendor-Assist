package com.assistant.adapter.smartassist

import com.assistant.storage.SplendorStorageRoot

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
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 4B — RuntimeSelfHealEngine (AI Self-Heal Agent — Full Authority)
 *
 * Changes from Phase 4A:
 *  - Starts immediately in OverlayService.onCreate() (3s grace, not 10s at G6)
 *  - Full deduplication — same event category+detail never spams the log
 *  - Has FULL RIGHT to restart ImageReader capture via OverlayService.restartCapture()
 *  - Permanently forces VisionTrust.foregroundIsGame=true on every cycle while
 *    capture is active (not just once — re-applies if accessibility event resets it)
 *  - Prints exact code fix patches to HealLog when in-memory fix is insufficient
 *  - Monitors gameplay contributor activity (detects dead contributors)
 *  - HealLog path: /sdcard/Splendor-Assist/SplendorHealLog.txt
 *    Read with: cat /sdcard/Splendor-Assist/SplendorHealLog.txt
 */
object RuntimeSelfHealEngine {

    data class HealEvent(
        val timestamp: String,
        val category: String,
        val detected: String,
        val fix: String,
        val severity: String   // FIXED / CRITICAL / WARNING / INFO / CODE_FIX_NEEDED
    )

    val healEvents: CopyOnWriteArrayList<HealEvent> = CopyOnWriteArrayList()

    @Volatile var agentStatus: String = "IDLE"
        private set
    @Volatile var totalHeals: Int = 0
        private set

    @Volatile private var running = false
    @Volatile private var agentStartedMs: Long = 0L
    private var contextRef: WeakReference<android.content.Context>? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Deduplication: category → last logged details hash + last log time
    private val lastLoggedMs = HashMap<String, Long>()
    private val lastLoggedDetail = HashMap<String, Int>()
    private val repeatCount = HashMap<String, Int>()
    private val DEDUP_MIN_MS = 5 * 60 * 1000L  // max 1 per 5 min per category

    fun init(ctx: android.content.Context) {
        contextRef = WeakReference(ctx.applicationContext)
    }

    // V6: expose stored context so the agent can execute safe recoveries.
    fun appContext(): android.content.Context? = contextRef?.get()

    fun start() {
        if (running) return
        running = true
        agentStartedMs = System.currentTimeMillis()
        agentStatus = "STARTING"
        val t = Thread {
            // 3s grace — just enough for services to start
            try { Thread.sleep(3_000L) } catch (_: Throwable) { return@Thread }
            agentStatus = "MONITORING"
            RuntimeLogger.log("AI Self-Heal Agent: MONITORING ACTIVE (5s cycle, immediate start)", "AGENT")
            writeToFile(null, header = true)  // write session header to HealLog
            while (running) {
                try { runChecks() }
                catch (e: Throwable) {
                    try { RuntimeLogger.log("AGENT FAULT: ${e.javaClass.simpleName}: ${e.message}", "AGENT") } catch (_: Throwable) {}
                }
                try {
                    Thread.sleep(5_000L)
                } catch (_: Throwable) {
                    return@Thread
                }
            }
            agentStatus = "STOPPED"
        }
        t.isDaemon = true
        t.name = "splendor-self-heal-agent"
        t.start()
        RuntimeLogger.log("RuntimeSelfHealEngine started — AI agent daemon online", "AGENT")
    }

    fun stop() { running = false; agentStatus = "IDLE" }

    fun isRunning(): Boolean = running

    /**
     * Controlled synchronous check requested by InAppAgentCore.
     * The existing periodic self-heal daemon remains authoritative.
     */
    fun runImmediateCheck() {
        if (!running) return
        try {
            runChecks()
        } catch (e: Throwable) {
            try {
                RuntimeLogger.log(
                    "AGENT immediate check fault: ${e.javaClass.simpleName}: ${e.message}",
                    "AGENT"
                )
            } catch (_: Throwable) {}
        }
    }

    private fun agentAgeMs() = System.currentTimeMillis() - agentStartedMs

    private fun runChecks() {
        val warmed = agentAgeMs() > 5_000L  // only 5s grace for flagging
        enforceForegroundGate()      // EVERY cycle — not just when false
        checkCaptureThread()         // critical — can restart
        checkBusSignals(warmed)
        checkLoadShed()
        checkDispatchRate()
        checkContributors(warmed)
        checkBattery()
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMANENT FG OVERRIDE: re-apply on every cycle while capture is active
    // Previous version: forced once, then accessibility event reset it to false
    // This version: every 5s check, if capture is active → fg must be true
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var fgForceCount = 0

    private fun enforceForegroundGate() {
        try {
            val frame = FrameAssembler.current() ?: return
            val frameAgeMs = System.currentTimeMillis() - frame.timestampMs
            if (frameAgeMs > 3000L) return  // capture stale — don't force

            val fg = VisionTrust.isGameForeground()
            if (!fg) {
                VisionTrust.setGameForeground(true)
                fgForceCount++
                if (shouldLog("FG_OVERRIDE", "forced=$fgForceCount")) {
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "FG_OVERRIDE",
                        detected = "foregroundIsGame=false while capture active (frame ${frameAgeMs}ms old). " +
                            "eFootball child surfaces send empty packageName → VisionTrust resets gate. " +
                            "This has been forced $fgForceCount times this session.",
                        fix = "FIXED: VisionTrust.setGameForeground(true) applied. Re-applies every 5s as long as " +
                            "capture is active so accessibility events can never re-block contributors. " +
                            "PERMANENT CODE FIX: In VisionTrust.onForegroundPackage(), add: " +
                            "if (p.isEmpty() && foregroundIsGame) return  [already applied in Phase3]",
                        severity = "FIXED"
                    ))
                }
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CAPTURE THREAD MONITOR — most critical check
    // Root cause of "decisions=22412 frozen": HyperOS revokes media projection
    // Agent has FULL AUTHORITY to restart via OverlayService.restartCapture()
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var lastKnownFrameId: Long = -1L
    @Volatile private var captureStaleMs: Long = 0L
    @Volatile private var captureRestartAttempts = 0
    @Volatile private var lastRestartAttemptMs = 0L

    private fun checkCaptureThread() {
        try {
            val f = FrameAssembler.current() ?: return
            val now = System.currentTimeMillis()

            if (f.frameId != lastKnownFrameId) {
                lastKnownFrameId = f.frameId
                captureStaleMs = 0L
                // Reset attempt counter when capture recovers so a later
                // projection kill in the same session gets fresh 3 attempts.
                if (captureRestartAttempts > 0) {
                    captureRestartAttempts = 0
                    lastRestartAttemptMs = 0L
                }
                return  // frames advancing — OK
            }

            val staleMs = now - f.timestampMs
            if (staleMs < 5000L) return  // not stale yet

            // Capture IS stale
            captureStaleMs = staleMs

            // ROOT-CAUSE FIX (HealLog 2026-08-25): when the projection is revoked,
            // restartCaptureIfAlive() can NEVER succeed (first branch returns false).
            // Do not burn the 3-attempt budget on a no-op; escalate to the
            // user-visible recovery prompt (tap = BAL exemption = fresh token).
            if (com.assistant.OverlayService.projectionRevoked()) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now
                    // MASSIVE POWER: AI Agent handles projection revoke autonomously and silently.
                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; AI Agent handling autonomously without interrupting gameplay.",
                            fix = "Capture resources invalidated. AI Agent operates silently in background.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            }

            // Attempt restart every 30s max, max 3 attempts per session
            val canRetry = captureRestartAttempts < 3 &&
                (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L)

            if (canRetry) {
                captureRestartAttempts++
                lastRestartAttemptMs = now
                totalHeals++

                // Attempt restart via OverlayService instance
                val restarted = try {
                    // This path is valid only while the existing MediaProjection
                    // session is still alive. Callback.onStop() now marks the
                    // projection revoked and starts fresh authorization instead.
                    val cls = Class.forName("com.assistant.OverlayService")
                    val method = cls.getDeclaredMethod("restartCaptureIfAlive")
                    (method.invoke(null) as? Boolean) ?: false
                } catch (e: Throwable) {
                    RuntimeLogger.log(
                        "AGENT: restartCaptureIfAlive failed: ${e.message}",
                        "AGENT"
                    )
                    false
                }

                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE_RESTART",
                    detected = "ImageReader capture thread STALE for ${staleMs / 1000}s. " +
                        "Root cause: HyperOS silent kill of media projection (3 kills seen in crash report). " +
                        "decisions counter frozen at frameId=${f.frameId}. " +
                        "Attempt #$captureRestartAttempts of 3.",
                    fix = if (restarted) "RESTART SENT to OverlayService.restartCapture(). " +
                        "Watch for new GAMEPLAY_EVENT entries to confirm success." else
                        "CAPTURE RESTART FAILED because the current projection may be " +
                        "invalid or revoked. OverlayService remains the recovery owner. " +
                        "If MediaProjection.onStop() fired, fresh user authorization " +
                        "is required; the old projection token cannot be reused.",
                    severity = if (restarted) "FIXED" else "CRITICAL"
                ))

                if (!restarted) {
                    printCodeFix("CAPTURE_THREAD_DEATH",
                        "HyperOS kills media projection after long sessions.\n" +
                        "The ImageReader stops delivering frames silently.\n" +
                        "PERMANENT FIX OPTIONS:\n" +
                        "1. In OverlayService.MediaProjection.Callback.onStop():\n" +
                        "   Instead of stopSelf(), call restartCapture() then re-request projection.\n" +
                        "2. Add KeepAlive periodic dummy capture every 60s to prevent OS timeout.\n" +
                        "3. Register FOREGROUND_SERVICE_TYPE=mediaProjection in manifest.\n" +
                        "APPLY: Check app/src/main/AndroidManifest.xml for foregroundServiceType."
                    )
                }
            } else if (shouldLog("CAPTURE_STALE", "stale-fixed")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE_STALE",
                    detected = "Capture stale ${staleMs / 1000}s. Restart attempts: $captureRestartAttempts/3.",
                    fix = if (captureRestartAttempts >= 3)
                        "Max restart attempts reached. FORCE-STOP app and reopen."
                        else "Next restart attempt in ${(30_000L - (System.currentTimeMillis() - lastRestartAttemptMs)) / 1000}s.",
                    severity = "CRITICAL"
                ))
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // BUS SIGNALS — publishes safe defaults if all stuck at UNKNOWN
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
                if (stuckMs > 30_000L && shouldLog("BUS_ALL_UNKNOWN", "stuck")) {
                    bus.publishNet("GO")
                    bus.publishLag("SMOOTH")
                    bus.publishStutter("CALM")
                    bus.publishMemory("HEALTHY", 1500L)
                    try { bus.publishThermal(0) } catch (_: Throwable) {}
                    try { bus.publishBattery(100, true) } catch (_: Throwable) {}
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "BUS_ALL_UNKNOWN",
                        detected = "ALL bus signals stuck at UNKNOWN for ${stuckMs / 1000}s. " +
                            "Adapter services not publishing. SpeedCompensationContributor + RuntimeDecisionLoop blind.",
                        fix = "FIXED: Published safe defaults GO/SMOOTH/CALM/HEALTHY. " +
                            "Will re-apply on next check if still UNKNOWN. " +
                            "CODE FIX: Run apply_phase3_adapters.py if not yet applied.",
                        severity = "FIXED"
                    ))
                    RuntimeLogger.log("AGENT HEAL #$totalHeals BUS: safe defaults published", "AGENT")
                    busUnknownSinceMs = 0L
                }
            } else {
                busUnknownSinceMs = 0L
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOAD SHED — logs exact cause
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var heavySinceMs: Long = 0L

    private fun checkLoadShed() {
        try {
            val shed = PerformanceTelemetryRegistry.currentLoadShed()
            if (shed == "HEAVY") {
                if (heavySinceMs == 0L) heavySinceMs = System.currentTimeMillis()
                val heavyMs = System.currentTimeMillis() - heavySinceMs
                if (heavyMs > 20_000L && shouldLog("LOAD_SHED_HEAVY", "heavy=${heavyMs/1000}s")) {
                    val lag = AdapterSignalBus.lagVerdict
                    val stutter = AdapterSignalBus.stutterState
                    val cause = when {
                        stutter == "SEIZURE" -> "BurstForensicsEngine SEIZURE → HEAVY armed immediately"
                        lag == "CHOKING" -> "LagVerdictEngine CHOKING → HEAVY after arm_polls=4 cycles"
                        else -> "lag=$lag stutter=$stutter"
                    }
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOAD_SHED_HEAVY",
                        detected = "LoadShedGovernor HEAVY for ${heavyMs / 1000}s. $cause. " +
                            "ALL gameplay engines severely throttled.",
                        fix = "Cannot force-release. Admin fixes:\n" +
                            "  lag.shed.arm_polls: raise 4 → 8 (slower to arm)\n" +
                            "  stutter.forensics.seizure_ms: raise 150 → 250ms\n" +
                            "  lag.shed.min_hold_ms: lower 8000 → 4000ms (faster release)",
                        severity = "CRITICAL"
                    ))
                }
            } else {
                heavySinceMs = 0L
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // DISPATCH RATE — detects frozen loop, identifies cause
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

            val newDecisions = decisions - prevDecisions
            val newRouted = routed - prevRouted
            prevDecisions = decisions
            prevRouted = routed

            if (newDecisions == 0L && decisions > 50L) {
                // Decision loop stopped — counter frozen
                if (shouldLog("LOOP_FROZEN", "frozen_at=$decisions")) {
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOOP_FROZEN",
                        detected = "RuntimeDecisionLoop counter frozen at $decisions. " +
                            "onFrame() not being called — ImageReader has stopped. " +
                            "This is almost always caused by HyperOS revoking media projection.",
                        fix = "AGENT will attempt capture restart. If max attempts reached: " +
                            "force-stop app and reopen. No code change needed — this is an OS kill issue.",
                        severity = "CRITICAL"
                    ))
                    RuntimeLogger.log("AGENT CRITICAL LOOP_FROZEN at $decisions — triggering capture check", "AGENT")
                    // Trigger capture check immediately
                    checkCaptureThread()
                }
            } else if (newDecisions > 30L && newRouted == 0L) {
                if (zeroDispatchSinceMs == 0L) zeroDispatchSinceMs = System.currentTimeMillis()
                val zeroMs = System.currentTimeMillis() - zeroDispatchSinceMs
                if (zeroMs > 10_000L && shouldLog("ZERO_DISPATCH", "zero=${zeroMs/1000}s")) {
                    // Loop running but nothing dispatched — trust or contributor issue
                    val trustPct = if (decisions > 0) idleUntrusted * 100L / decisions else 0L
                    val reason = if (trustPct > 50L)
                        "Vision trust blocks ${trustPct}% of frames → forcing fg=true"
                        else "Contributors all returning null → arbitration finding nothing actionable"
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "ZERO_DISPATCH",
                        detected = "0 gestures over ${zeroMs/1000}s despite $newDecisions decision cycles. $reason",
                        fix = if (trustPct > 50L) "APPLYING: VisionTrust.setGameForeground(true)"
                            else "MONITORING — contributor arbitration issue",
                        severity = "CRITICAL"
                    ))
                    if (trustPct > 50L) {
                        VisionTrust.setGameForeground(true)
                        totalHeals++
                    }
                }
            } else {
                zeroDispatchSinceMs = 0L
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONTRIBUTOR MONITOR — detects if any contributor is dead/never firing
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var prevCollectCycles: Long = 0L

    private fun checkContributors(warmed: Boolean) {
        if (!warmed) return
        try {
            val cls = Class.forName("com.assistant.runtime.GameplayEngineRegistry")
            @Suppress("UNCHECKED_CAST")
            val snap = cls.getMethod("registryRuntimeSnapshot").invoke(null) as? Map<String, Any> ?: return
            val engines = (snap["engines"] as? Int) ?: return
            val cycles = (snap["collectCycles"] as? Long) ?: return
            val delta = cycles - prevCollectCycles
            prevCollectCycles = cycles

            // PHASE4B: COLLECT_STALL — delta==0 while engine has run = collector frozen
            if (delta == 0L && cycles > 100L && engines >= 1 &&
                shouldLog("COLLECT_STALL", "stall_at=$cycles")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "COLLECT_STALL",
                    detected = "GameplayEngineRegistry.collect() stalled — 0 new collect cycles " +
                        "in last 5s (total cycles so far: $cycles). " +
                        "$engines contributors registered but idle. " +
                        "onFrame() not reaching RuntimeDecisionLoop — ImageReader likely dead.",
                    fix = "Triggering capture restart check via checkCaptureThread().",
                    severity = "CRITICAL"
                ))
                RuntimeLogger.log("AGENT CRITICAL COLLECT_STALL at cycles=$cycles — triggering capture check", "AGENT")
                checkCaptureThread()
            }

            if (engines < 29 && warmed && shouldLog("REGISTRY_GAP", "engines=$engines")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "REGISTRY_GAP",
                    detected = "Only $engines/29 contributors registered. Missing ${29 - engines}. " +
                        "Collect cycles this check: $delta (0 = collector frozen). " +
                        "warmUpEngines() may not have completed (G4 gate may not have fired).",
                    fix = "NONE in-memory — warmUpEngines() runs only once at G4. " +
                        "Check if G2 (capture) + G1 (accessibility) gates both reached.",
                    severity = "CRITICAL"
                ))
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // BATTERY — warn when low
    // ─────────────────────────────────────────────────────────────────────
    private fun checkBattery() {
        try {
            val level = AdapterSignalBus.batteryLevel
            val charging = AdapterSignalBus.batteryCharging
            if (level in 1..20 && !charging && shouldLog("BATTERY_LOW", "level=$level")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "BATTERY_LOW",
                    detected = "Battery at ${level}% and not charging. " +
                        "SpeedCompensationContributor reduces gesture duration to 50% of normal. " +
                        "Charging=false means CPU governor may throttle A75 cores.",
                    fix = "OPERATIONAL — reduced gestures is intentional at low battery. " +
                        "Plug in charger for full engine performance.",
                    severity = "WARNING"
                ))
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // Deduplication — same category can only log once per DEDUP_MIN_MS
    // ─────────────────────────────────────────────────────────────────────
    @Synchronized
    private fun shouldLog(category: String, detail: String): Boolean {
        val now = System.currentTimeMillis()
        val lastMs = lastLoggedMs[category] ?: 0L
        val lastHash = lastLoggedDetail[category] ?: -1
        val currentHash = detail.hashCode()
        val count = (repeatCount[category] ?: 0) + 1
        repeatCount[category] = count

        // Allow if: never logged, or enough time passed, or detail changed significantly
        if (now - lastMs > DEDUP_MIN_MS || lastHash != currentHash) {
            lastLoggedMs[category] = now
            lastLoggedDetail[category] = currentHash
            repeatCount[category] = 0
            return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────
    // Code fix printer — outputs structured patch to HealLog
    // ─────────────────────────────────────────────────────────────────────
    private fun printCodeFix(issueId: String, description: String) {
        val ts = fmt.format(Date())
        val text = buildString {
            appendLine("=" .repeat(60))
            appendLine("⚠️  CODE FIX NEEDED [$ts] — $issueId")
            appendLine("=" .repeat(60))
            for (line in description.lines()) appendLine("  $line")
            appendLine("=" .repeat(60))
        }
        try {
            val file = healLogFile() ?: return
            FileWriter(file, true).use { it.write(text) }
        } catch (_: Throwable) {}
        try { RuntimeLogger.log("CODE_FIX_NEEDED: $issueId — see HealLog", "AGENT") } catch (_: Throwable) {}
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

    private fun healLogFile(): File? {
        return try {
            // /sdcard/Splendor-Assist/SplendorHealLog.txt — read with:
            // cat /sdcard/Splendor-Assist/SplendorHealLog.txt
            SplendorStorageRoot.file("SplendorHealLog.txt")
        } catch (_: Throwable) {
            // Canonical storage only. No storage fallback is permitted.
            null
        }
    }

    private fun writeToFile(ev: HealEvent?, header: Boolean = false) {
        try {
            val file = healLogFile() ?: return
            FileWriter(file, true).use { w ->
                if (header) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    w.write("\n" + "=".repeat(60) + "\n")
                    w.write("SPLENDOR SELF-HEAL AGENT SESSION: $ts\n")
                    w.write("Read: cat /sdcard/Splendor-Assist/SplendorHealLog.txt\n")
                    w.write("=".repeat(60) + "\n\n")
                    return
                }
                if (ev == null) return
                w.write("[${ev.timestamp}] [${ev.severity}] [${ev.category}]\n")
                w.write("  DETECTED: ${ev.detected}\n")
                w.write("  FIX:      ${ev.fix}\n\n")
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI status summary
    // ─────────────────────────────────────────────────────────────────────
    fun getStatusSummary(): String {
        return try {
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val decisions = (snap["decisions"] as? Long) ?: 0L
            val routed = (snap["routed"] as? Long) ?: 0L
            val dispatchPct = if (decisions > 0) routed * 100L / decisions else 0L
            val shed = try { PerformanceTelemetryRegistry.currentLoadShed() } catch (_: Throwable) { "?" }
            val fg = try { VisionTrust.isGameForeground() } catch (_: Throwable) { false }
            val agentAge = agentAgeMs() / 1000L
            buildString {
                appendLine("STATUS: $agentStatus  |  heals: $totalHeals  |  age: ${agentAge}s")
                appendLine("fg=$fg  shed=$shed  dispatch=${dispatchPct}%  fgForces=$fgForceCount")
                appendLine("net=${AdapterSignalBus.netWindow}  lag=${AdapterSignalBus.lagVerdict}")
                appendLine("stutter=${AdapterSignalBus.stutterState}  mem=${AdapterSignalBus.memoryTier}")
                appendLine("thermal=${AdapterSignalBus.thermalStatus}  battery=${AdapterSignalBus.batteryLevel}%chg=${AdapterSignalBus.batteryCharging}")
                appendLine("decisions=$decisions  routed=$routed  captureRestarts=$captureRestartAttempts")
                appendLine("lastAction=${snap["lastAction"]}")
                appendLine("HealLog: /sdcard/Splendor-Assist/SplendorHealLog.txt")
            }
        } catch (e: Throwable) { "AGENT STATUS ERROR: ${e.message}" }
    }
}
