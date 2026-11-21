package com.assistant

// F49_PATCH_V1









import android.content.Context

















import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger


import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry






import com.assistant.runtime.*




import com.assistant.runtime.GameplayEngineRegistry


import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference



import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList








import kotlin.math.*




























object RuntimeSelfHealEngine {

    data class HealEvent(
        val timestamp: String,
        val category: String,
        val detected: String,
        val fix: String,
        val severity: String,  // FIXED / CRITICAL / WARNING / INFO / CODE_FIX_NEEDED
        // SPLENDOR_P12_T4_SOURCE: call-site location captured by record();
        // empty when the event predates this patch.
        val source: String = ""
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

    // Deduplication: category → last log time (max 1 per 5 min per category).
    // SPLENDOR_P11_RSE_DEDUP: the per-detail hash bookkeeping
    // (lastLoggedDetail) and the write-only repeatCount are deleted: the hash
    // bypass they fed is removed from shouldLog(), so they have no readers.
    private val lastLoggedMs = HashMap<String, Long>()
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
        // SPLENDOR_F34_FG_ENFORCE: re-enabled so capture active keeps foregroundIsGame true.
        enforceForegroundGate()
        checkCaptureThread()         // critical — can restart
        checkBusSignals(warmed)
        checkLoadShed()
        checkDispatchRate()
        checkContributors(warmed)
        checkBattery()
        // B3: re-establish the gate's consumer when G1 is lost (never a
        // bus restart — the recovery engine only repairs the consumer
        // thread; the coordinator's G5 path re-enables the bus).
        // Letter A item 1: the RecoveryDecision return used to be
        // discarded here — the decision (RECOVER_CONSUMER /
        // WAIT_FOR_SERVICE) never reached any consumer. Read it now and
        // record a heal event for every non-NO_ACTION decision, so the
        // decision is visible in healEvents (AgentHubActivity last-20),
        // the HealLog file and the AGENT log. NO_ACTION is not recorded
        // (it fires on every healthy 5 s cycle). shouldLog() dedups to
        // at most one event per 5 min for the category, so a stuck gate
        // cannot flood the log. The event records the DECISION only —
        // checkAndRecover() itself logs GATE_RECOVERED /
        // GATE_RECOVERY_FAILED, which is where the outcome lives.
        try {
            val gateDecision = AccessibilityGateRecoveryEngine.checkAndRecover()
            if (gateDecision != AccessibilityGateRecoveryEngine.RecoveryDecision.NO_ACTION &&
                shouldLog("GATE_RECOVERY", gateDecision.name)
            ) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "GATE_RECOVERY",
                    detected = "accessibility gate recovery decision=$gateDecision (gate lost, capture alive, consumer dead)",
                    fix = "RecoveryDecision consumed by self-heal: consumer restart attempted for RECOVER_CONSUMER; WAIT_FOR_SERVICE means no service instance exists (only the system can rebind it)",
                    severity = if (gateDecision == AccessibilityGateRecoveryEngine.RecoveryDecision.RECOVER_CONSUMER) "INFO" else "WARNING"
                ))
            }
        } catch (_: Throwable) { }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMANENT FG OVERRIDE: re-apply on every cycle while capture is active
    // Previous version: forced once, then accessibility event reset it to false
    // This version: every 5s check, if capture is active → fg must be true
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var fgForceCount = 0

    private fun enforceForegroundGate() {
        try {
            // No62-D21: our own package is NON-BLOCKING for the
            // accessibility feed (SmartAssistAccessibilityEngine:458 —
            // packageName returns true), so the package guard below
            // cannot see our own Control Room / Diagnosis pages: when
            // one is open, lastForegroundPkg still names the game and
            // isKnownNonGameForeground() is false. The old code then
            // forced fg=true on every 5s cycle while own UI was visible
            // — fighting ForegroundGate, flipping the stored field, and
            // logging a misleading "FG_OVERRIDE suppressed by GAME
            // override or package policy" line each time (the real
            // suppressor is ForegroundGate.ownUiVisible(), read
            // directly by VisionTrust.isGameForeground()). The gate owns
            // the decision while any of our activities is visible.
            if (com.assistant.vision.ForegroundGate.ownUiVisible()) return
            val frame = FrameAssembler.current() ?: return
            val frameAgeMs = System.currentTimeMillis() - frame.timestampMs
            if (frameAgeMs > 3000L) return  // capture stale — don't force

            // SPLENDOR_P12_T4_FG: forcing foreground=true with a known
            // non-game app in front made fg=true for non-game packages.
            // Empty package (eFootball child surfaces) still forces.
            if (VisionTrust.isKnownNonGameForeground()) return

            val fg = VisionTrust.isGameForeground()
            if (!fg) {
                VisionTrust.setGameForeground(true)
                val recovered = VisionTrust.isGameForeground()
                if (!recovered) {
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "FG_OVERRIDE suppressed by GAME override or package policy", "SELF_HEAL")
                    return
                }

                fgForceCount++
                if (shouldLog("FG_OVERRIDE", "forced=$fgForceCount")) {
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "FG_OVERRIDE",
                        detected = "foregroundIsGame=false while capture active (frame ${frameAgeMs}ms old). " +
                            "eFootball child surfaces send empty packageName → VisionTrust resets gate. " +
                            "This has been forced $fgForceCount times this session.",
                        fix = if (VisionTrust.isGameForeground()) "POLICY APPLIED: VisionTrust accepted foreground recovery" else "SUPPRESSED: GAME override or package policy prevented foreground recovery",
                        severity = "FIXED"
                    ))
                }
            }
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CAPTURE THREAD MONITOR — most critical check
    // Root cause of "decisions=22412 frozen": HyperOS revokes media projection
    // Agent has FULL AUTHORITY to restart via OverlayService.restartCapture()
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var lastKnownFrameId: Long = -1L
    @Volatile private var captureRestartAttempts = 0
    @Volatile private var lastRestartAttemptMs = 0L
    // F37-D5: idle/auto-heal branches spend no attempt, so they throttle on
    // their own clock (lastIdleHealMs) instead of the attempt budget.
    @Volatile private var lastIdleHealMs = 0L
    // F37-D12: session-scoped restart count — the budget counter above
    // resets on any new frame, so "Attempt #1 of 3" could repeat in one
    // session. This one never resets except on process start.
    @Volatile private var captureRestartSessionTotal = 0

    private fun checkCaptureThread() {
        try {
            val f = FrameAssembler.current()
            val now = System.currentTimeMillis()

            // SPLENDOR_V42_NULL_FRAME_FIX_BEGIN
            // Field log 2026-10-02 15:47: capture=0x0 -> FrameAssembler.current()
            // stays null -> old `?: return` made COLLECT_ZERO unhealable.
            if (f == null) {
                val state = com.assistant.OverlayService.captureState()
                if (state == com.assistant.OverlayService.CaptureState.ACTIVE ||
                    state == com.assistant.OverlayService.CaptureState.AUTHORIZED) {
                    val canRetry = captureRestartAttempts < 3 &&
                        (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L)
                    if (canRetry) {
                        captureRestartAttempts++
                        captureRestartSessionTotal++
                        lastRestartAttemptMs = now
                        totalHeals++
                        val restarted = try {
                            val cls = Class.forName("com.assistant.OverlayService")
                            val method = cls.getDeclaredMethod("restartCaptureIfAlive")
                            (method.invoke(null) as? Boolean) ?: false
                        } catch (e: Throwable) {
                            RuntimeLogger.log("AGENT: restartCaptureIfAlive failed: ${e.message}", "AGENT")
                            false
                        }
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_RESTART",
                            detected = "No frame ever assembled (capture=" + com.assistant.vision.CameraProfile.diagnostics() + "). " +
                                "Attempt #$captureRestartAttempts of 3 (session #$captureRestartSessionTotal).",
                            fix = if (restarted) "RESTART SENT to OverlayService.restartCapture(); awaiting frame resumption for verification." else
                                "CAPTURE RESTART FAILED — capture state " + com.assistant.OverlayService.captureState() + ".",
                            severity = if (restarted) "INFO" else "CRITICAL"
                        ))
                    } else {
                        // SPLENDOR_P11_D4: heal first, log second. The
                        // auto-heal request used to sit inside the shouldLog()
                        // block, so recovery was throttled by the 5-min log
                        // dedup. requestAutoHeal() is safe to call every
                        // cycle: OverlayService.autoHealCapture() has its own
                        // 15s anti-spam cooldown.
                        try { com.assistant.OverlayService.requestAutoHeal() } catch (_: Throwable) {}
                        // F37-D5: EXHAUSTED used to fire whenever the 30s
                        // throttle blocked a retry — with 0 attempts spent —
                        // and hardcoded "3 attempts failed". Gate it on the
                        // real budget and report the real count.
                        if (captureRestartAttempts >= 3 && shouldLog("CAPTURE_RESTART_EXHAUSTED", "attempts=$captureRestartAttempts")) {
                            // SPLENDOR_P6_RESTART_EXHAUSTED_BEGIN
                            // Restart budget spent (3 failed attempts) with still no
                            // frame: escalate to auto-heal instead of going silent.
                            totalHeals++
                            record(HealEvent(
                                timestamp = fmt.format(Date()),
                                category = "CAPTURE_RESTART_EXHAUSTED",
                                detected = "No frame ever assembled; $captureRestartAttempts capture restart attempts failed (capture=" + com.assistant.vision.CameraProfile.diagnostics() + ").",
                                fix = "Auto-heal requested (token reuse if the session is alive, else system consent dialog).",
                                severity = "CRITICAL"
                            ))
                            // SPLENDOR_P6_RESTART_EXHAUSTED_END
                        }
                    }
                } else {
                    // SPLENDOR_P6_NEVER_STARTED_BEGIN
                    // Session-2 zombie (2026-11-12 17:21-19:46): no frame ever
                    // assembled + capture IDLE -> this branch returned silently,
                    // so COLLECT_ZERO fired 5,205x with zero CAPTURE_IDLE lines.
                    // IDLE/FAILED/REVOKED with no frame means capture never
                    // started (or died before the first frame): log it, record
                    // it, and attempt auto-heal instead of returning silently.
                    // Throttled to one attempt per 30s; autoHealCapture has its
                    // own 15s cooldown. restartCaptureIfAlive is NOT used here:
                    // it returns false when mediaProjection is null / revoked.
                    // F37-D5: this branch spends no attempt, so it must not
                    // consume the attempt-budget throttle (lastRestartAttemptMs)
                    // — doing so poisoned canRetry and fired a false EXHAUSTED.
                    // Batch 6c (v219 item b): STARTUP GRACE — this branch
                    // fired a CRITICAL CAPTURE_IDLE three times in the
                    // 2026-11-19 v219 session while capture was still
                    // starting, not dead:
                    //  - 16:34:15.321: InAppAgentCore cycle 12 drove
                    //    runImmediateCheck() BEFORE OverlayService existed
                    //    in pid 30702 ("OverlayService started" 16:34:16.399),
                    //    so instance was null and captureState() fell back
                    //    to IDLE (OverlayService.kt:104-105). 1.1 s later
                    //    the service started and the first frame arrived
                    //    16:34:17.040.
                    //  - 16:35:54.823 (pid 962) + 16:51:27.732 (pid 20195):
                    //    the agent thread's FIRST cycle after its 3 s grace
                    //    (~3.3 s after start) ran while onStartCommand was
                    //    still executing (FGS crash 0.35 s / 1.46 s later),
                    //    so the instance's own captureState field was still
                    //    its IDLE initial value. The 3 s grace in start()
                    //    cannot cover either path: runImmediateCheck()
                    //    bypasses it, and 3 s is shorter than a service
                    //    start. The grace lives HERE, in the branch, on
                    //    agent age (agentStartedMs, set in start()): under
                    //    30 s old, a no-frame + IDLE reading is startup,
                    //    not death — skip silently (no CRITICAL, no
                    //    auto-heal, no throttle consumption). 30 s covers
                    //    the session's slowest start (23 s to first frame)
                    //    with margin, and matches this branch's own 30 s
                    //    throttle; a genuinely dead capture still fires
                    //    once the agent is older than 30 s. The ACTIVE /
                    //    AUTHORIZED restart branch above is untouched.
                    //    The detected text now carries the agent age so a
                    //    future reader can tell startup from death.
                    if (agentStartedMs > 0L && now - agentStartedMs < 30_000L) {
                        return
                    }
                    if (now - lastIdleHealMs > 30_000L || lastIdleHealMs == 0L) {
                        lastIdleHealMs = now
                        totalHeals++
                        try { com.assistant.OverlayService.requestAutoHeal() } catch (_: Throwable) {}
                        val neverStartedCategory = "CAPTURE_" + state.name
                        if (shouldLog(neverStartedCategory, "never_started state=$state")) {
                            record(HealEvent(
                                timestamp = fmt.format(Date()),
                                category = neverStartedCategory,
                                detected = "No frame ever assembled and capture is $state (never started, or died before the first frame). agentAge=${(now - agentStartedMs) / 1000L}s. capture=" + com.assistant.vision.CameraProfile.diagnostics() + ".",
                                fix = "Auto-heal requested (token reuse if the session is alive, else system consent dialog). If the service was restarted without a token, tap the capture recovery prompt.",
                                severity = "CRITICAL"
                            ))
                        }
                    }
                    // SPLENDOR_P6_NEVER_STARTED_END
                }
                return
            }
            // SPLENDOR_V42_NULL_FRAME_FIX_END

            if (f.frameId != lastKnownFrameId) {
                lastKnownFrameId = f.frameId
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

            // ROOT-CAUSE FIX (HealLog 2026-08-25): when the projection is revoked,
            // restartCaptureIfAlive() can NEVER succeed (first branch returns false).
            // Do not burn the 3-attempt budget on a no-op; escalate to the
            // user-visible recovery prompt (tap = BAL exemption = fresh token).
            val state = com.assistant.OverlayService.captureState()
            if (state == com.assistant.OverlayService.CaptureState.REVOKED) {
                if (now - lastIdleHealMs > 30_000L || lastIdleHealMs == 0L) {
                    lastIdleHealMs = now
                    // SPLENDOR_V42_AUTOHEAL_FIX_BEGIN
                    // v2 (2026-10-02): automatic recovery — no manual tap.
                    // 1) token reuse (zero interaction) if the projection session
                    //    is still alive; 2) otherwise auto-launch the system
                    //    consent dialog (one tap — Android 14 requires one
                    //    consent per capture session; no app can bypass it).
                    try { com.assistant.OverlayService.requestAutoHeal() } catch (_: Throwable) {}
                    // SPLENDOR_V42_AUTOHEAL_FIX_END
                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; auto-heal triggered (token reuse or system consent).",
                            fix = "Auto-heal: token reuse if session alive, else system consent dialog (single tap).",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            } else if (state == com.assistant.OverlayService.CaptureState.FAILED) {
                if (now - lastIdleHealMs > 30_000L || lastIdleHealMs == 0L) {
                    lastIdleHealMs = now
                    // SPLENDOR_P6_STALE_FAILED_AUTOHEAL_BEGIN
                    // Was log-only ("AI Agent handling autonomously") while
                    // doing nothing: same silent no-op class as the f==null
                    // IDLE branch. Attempt auto-heal like the REVOKED branch.
                    try { com.assistant.OverlayService.requestAutoHeal() } catch (_: Throwable) {}
                    // SPLENDOR_P6_STALE_FAILED_AUTOHEAL_END
                    if (shouldLog("CAPTURE_FAILED", "failed")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_FAILED",
                            detected = "MediaProjection setup failed; capture stale with no fresh frame.",
                            fix = "Auto-heal requested (token reuse if the session is alive, else system consent dialog).",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            } else if (state == com.assistant.OverlayService.CaptureState.IDLE) {
                if (now - lastIdleHealMs > 30_000L || lastIdleHealMs == 0L) {
                    lastIdleHealMs = now
                    // SPLENDOR_P6_STALE_IDLE_AUTOHEAL_BEGIN
                    // Was log-only ("AI Agent handling autonomously") while
                    // doing nothing: same silent no-op class as the f==null
                    // IDLE branch. Attempt auto-heal like the REVOKED branch.
                    try { com.assistant.OverlayService.requestAutoHeal() } catch (_: Throwable) {}
                    // SPLENDOR_P6_STALE_IDLE_AUTOHEAL_END
                    if (shouldLog("CAPTURE_IDLE", "idle")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_IDLE",
                            detected = "MediaProjection idle; capture stale with no fresh frame.",
                            fix = "Auto-heal requested (token reuse if the session is alive, else system consent dialog).",
                            severity = "WARNING"
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
                captureRestartSessionTotal++
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
                        "Root cause: HyperOS silent kill of media projection (unverified hypothesis — no kill count is tracked). " +
                        "decisions counter frozen at frameId=${f.frameId}. " +
                        "Attempt #$captureRestartAttempts of 3 (session #$captureRestartSessionTotal).",
                    fix = if (restarted) "RESTART SENT to OverlayService.restartCapture(); awaiting frame resumption for verification." else
                        "CAPTURE RESTART FAILED because the current projection may be " +
                        "invalid or revoked. OverlayService remains the recovery owner. " +
                        "If MediaProjection.onStop() fired, fresh user authorization " +
                        "is required; the old projection token cannot be reused.",
                    severity = if (restarted) "INFO" else "CRITICAL"
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
                        "Max restart attempts reached. If still frozen, re-grant MediaProjection via the capture recovery prompt; no automatic force-stop exists."
                        else "Next restart attempt in ${(30_000L - (System.currentTimeMillis() - lastRestartAttemptMs)) / 1000}s.",
                    severity = "CRITICAL"
                ))
                // N4: the 3-attempt budget used to exhaust permanently
                // (19:11:28 in the 2026-11-18 session), after which nothing
                // watched the vision path for 21 minutes. Re-arm it on
                // this 5-min stale cadence while the frame is still frozen,
                // so the heal keeps attempting restarts (3 per 5 min)
                // instead of going silent. Only when the budget is actually
                // exhausted; the 30 s spacing between attempts is unchanged.
                if (captureRestartAttempts >= 3) {
                    captureRestartAttempts = 0
                    RuntimeLogger.log("CAPTURE_RESTART_BUDGET_RESET: restart budget exhausted while capture still stale; re-armed to 0/3 (next attempt in ~30s)", "AGENT")
                }
            }
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BUS SIGNALS — publishes safe defaults if all stuck at UNKNOWN
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var busUnknownSinceMs: Long = 0L

    private fun checkBusSignals(warmed: Boolean) {
        if (!warmed) return
        try {
            // N3 (f58): mirror the :survival-published signals into this
            // process's bus copy before judging it — without this the
            // check below could never clear (see MainProcessBusMirror).
            MainProcessBusMirror.mirrorIfDue()
            val bus = AdapterSignalBus
            val allUnknown = bus.netWindow == "UNKNOWN" && bus.lagVerdict == "UNKNOWN" &&
                    bus.stutterState == "UNKNOWN" && bus.memoryTier == "UNKNOWN"

            if (allUnknown) {
                if (busUnknownSinceMs == 0L) busUnknownSinceMs = System.currentTimeMillis()
                val stuckMs = System.currentTimeMillis() - busUnknownSinceMs
                if (stuckMs > 30_000L && shouldLog("BUS_ALL_UNKNOWN", "stuck")) {
                    // no fabricated signals: stuck UNKNOWN is reported, not replaced
                    // SPLENDOR_P11_D3: report-only event — do not totalHeals++
                    // for it (the old increment inflated the heal counter).
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "BUS_ALL_UNKNOWN",
                        detected = "ALL bus signals stuck at UNKNOWN for ${stuckMs / 1000}s. " +
                            "Adapter services not publishing. SpeedCompensationContributor + RuntimeDecisionLoop blind.",
                        fix = "REPORTED: signals stuck UNKNOWN; no values were injected.",
                        severity = "WARNING"
                    ))
                    RuntimeLogger.log("AGENT BUS: all signals stuck UNKNOWN — reported, no values injected", "AGENT")
                    // F37-D12: do NOT reset busUnknownSinceMs here — the old
                    // reset made every report read ~295s (time since the last
                    // report) instead of the true continuous UNKNOWN time.
                    // The start now clears only when a signal recovers (below).
                }
            } else {
                busUnknownSinceMs = 0L
            }
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
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
                    // SPLENDOR_P12_T4_PURGE: AggressiveMemoryHoarding.executePurge
                    // existed with zero callers while HEAVY only logged. Run
                    // it (its own 60s cooldown is the spam guard).
                    try { appContext()?.let { com.assistant.AggressiveMemoryHoarding.executePurge(it) } } catch (_: Throwable) {}
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
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DISPATCH RATE — detects frozen loop, identifies cause
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var prevDecisions: Long = 0L
    @Volatile private var prevRouted: Long = 0L
    // SPLENDOR_P6_DEDUP_TRIAGE_FIELD_BEGIN
    // Last assembled frameId seen by checkDispatchRate; used to tell a real
    // capture death (frameId frozen) from dedup-suppressed onFrame calls.
    @Volatile private var prevDispatchFrameId: Long = -1L
    // F37-D6: previous decisions-vs-frameId gap, for gap-growth gating.
    @Volatile private var prevStaticGap: Long = -1L
    // SPLENDOR_P6_DEDUP_TRIAGE_FIELD_END
    @Volatile private var zeroDispatchSinceMs: Long = 0L

    // F38-D3: decision-exit counters as one log line, so a stall firing
    // attributes itself in the log file. The snapshot previously lived
    // only in the control-room UI — in the 2026-11-16 live session the
    // counters appeared in NO log file, so collect() frozen at 2,071
    // could not be attributed to any onFrame exit.
    private fun decisionExitCounters(): String {
        return try {
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            "decisions=${snap["decisions"] as? Long ?: 0L} " +
                "routed=${snap["routed"] as? Long ?: 0L} " +
                "gateBlocked=${snap["gateBlocked"] as? Long ?: 0L} " +
                "staleFrame=${snap["staleFrame"] as? Long ?: 0L} " +
                "settingsExit=${snap["settingsExit"] as? Long ?: 0L} " +
                "routeRejected=${snap["routeRejected"] as? Long ?: 0L} " +
                "chooseNull=${snap["chooseNull"] as? Long ?: 0L} " +
                "idleUntrusted=${snap["idleUntrusted"] as? Long ?: 0L} " +
                "idleFg=${snap["idleUntrustedFg"] as? Long ?: 0L} " +
                "idleFrame=${snap["idleUntrustedFrame"] as? Long ?: 0L} " +
                "idleVariance=${snap["idleUntrustedVariance"] as? Long ?: 0L} " +
                "idleNoContribution=${snap["idleNoContribution"] as? Long ?: 0L}"
        } catch (_: Throwable) { "decisionExitCounters=unavailable" }
    }

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
            // F37-D6: the loop is healthy again — reset the static-gap
            // baseline so the next freeze is judged on fresh growth.
            if (newDecisions != 0L) prevStaticGap = -1L

            if (newDecisions == 0L && decisions > 50L) {
                // Decision loop stopped — counter frozen
                // SPLENDOR_P6_DEDUP_TRIAGE_BEGIN
                // Distinguish "capture dead" from "frames arriving but
                // identical": the OverlayService dedup path skips onFrame
                // for byte-identical frames, so the decision counter can
                // freeze while capture is alive. If the assembled frame
                // advanced since the last frozen check, this is a downstream
                // stall, not a capture death: record LOOP_STATIC and do NOT
                // trigger a capture restart.
                val dispatchFrameId = try { FrameAssembler.current()?.frameId ?: -1L } catch (_: Throwable) { -1L }
                val frameAdvanced = dispatchFrameId != -1L && prevDispatchFrameId != -1L && dispatchFrameId != prevDispatchFrameId
                prevDispatchFrameId = dispatchFrameId
                if (frameAdvanced) {
                    // F37-D6: STATIC must fire on gap GROWTH, not on the
                    // cumulative historic gap (decisions vs frameId differ by
                    // a stable ~9 frames for the whole session), and dedup
                    // cannot cause this: it skips assemble(), so frameId
                    // would not advance.
                    val staticGap = dispatchFrameId - decisions
                    val gapGrew = prevStaticGap < 0L || staticGap > prevStaticGap
                    prevStaticGap = staticGap
                    if (gapGrew && shouldLog("LOOP_STATIC", "static_at=$decisions frame=$dispatchFrameId gap=$staticGap")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "LOOP_STATIC",
                            detected = "RuntimeDecisionLoop counter frozen at $decisions but assembled frames are still advancing (frameId=$dispatchFrameId, gap=$staticGap). Capture is delivering; onFrame is being skipped downstream (UiExecutionGate-blocked, stale-frame, SETTINGS, or route-rejected exits — see the decision snapshot counters), not a capture death. Decision exits: ${decisionExitCounters()}",
                            fix = "No capture restart triggered. Decision exits are in the detected line above.",
                            severity = "WARNING"
                        ))
                    }
                } else if (shouldLog("LOOP_FROZEN", "frozen_at=$decisions")) {
                // SPLENDOR_P6_DEDUP_TRIAGE_END
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOOP_FROZEN",
                        detected = "RuntimeDecisionLoop counter frozen at $decisions. " +
                            "onFrame() not being called — decision counter did not advance since last check. " +
                            "Upstream cause is NOT established by this check: ImageReader may still be delivering " +
                            "(check CAPTURE_IMAGE breadcrumbs) while the vision coroutine is wedged " +
                            "(VISION_BUSY_SKIP) and dropping every frame before onFrame. " +
                            "See the capture check result for which. Decision exits: ${decisionExitCounters()}",
                        fix = "AGENT will attempt capture restart. If max attempts reached: " +
                            "re-grant MediaProjection via the capture recovery prompt; no automatic force-stop exists.",
                        severity = "CRITICAL"
                    ))
                    RuntimeLogger.log("AGENT CRITICAL LOOP_FROZEN at $decisions — triggering capture check. Decision exits: ${decisionExitCounters()}", "AGENT")
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
                        fix = if (trustPct > 50L) "foreground recovery is attempted through VisionTrust policy; see FG_OVERRIDE result"
                            else "MONITORING — contributor arbitration issue",
                        severity = "CRITICAL"
                    ))
                    if (trustPct > 50L) {
                        totalHeals++
                    }
                }
            } else {
                zeroDispatchSinceMs = 0L
            }
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONTRIBUTOR MONITOR — detects if any contributor is dead/never firing
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var prevCollectCycles: Long = 0L
    // SPLENDOR_P12_T4_REGISTRY_PERSIST: first-seen time of an empty registry
    // read; reset() is only safe once the empty read has persisted.
    @Volatile private var registryEmptySinceMs: Long = 0L

    private fun checkContributors(warmed: Boolean) {
        if (!warmed) return
        try {
            // SPLENDOR_V18_CONTRIB_HEALTH_BEGIN
            val engines = GameplayEngineRegistry.contributorCount()
            if (engines > 0) registryEmptySinceMs = 0L
            val cycles = GameplayEngineRegistry.collectCycleCount()
            val delta = cycles - prevCollectCycles
            prevCollectCycles = cycles
            com.assistant.NativeBridge.logNativeTruthOnce()
            val ageMs = agentAgeMs()

            if (engines == 0 && ageMs > 5_000L) {
                // SPLENDOR_P12_T4_REGISTRY_PERSIST: a single 0 read used to
                // reset() immediately, cancelling the in-flight async
                // registration and wiping the registry (REGISTRY_EMPTY/GAP
                // race). Require the empty read to persist 10s first.
                if (registryEmptySinceMs == 0L) registryEmptySinceMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - registryEmptySinceMs > 10_000L) {
                    try { com.assistant.AppContributorRegistration.reset() } catch (_: Throwable) {}
                    try { com.assistant.AppContributorRegistration.ensureRegistered() } catch (_: Throwable) {}
                    // F37-D5: the REGISTRY_EMPTY record used to fire on the
                    // first warmed cycle with 0 engines — before the 10s
                    // persist guard above elapsed — i.e. during normal async
                    // warmup. It now fires only after the empty read persists.
                    if (shouldLog("REGISTRY_EMPTY", "engines=0") /* SPLENDOR_P9_RSE_DEDUP: no per-second age token -- it defeated the 5-min dedup */) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "REGISTRY_EMPTY",
                            detected = "RuntimeSelfHealAgent observed 0 registered contributors after warm grace. collectCycles=$cycles. " +
                                    "Snapshot: ${GameplayEngineRegistry.registryRuntimeSnapshot()}. " +
                                    "Registration is asynchronous (AppContributorRegistration.ensureRegistered() runs from the image-frame path); " +
                                    "this may be a point-in-time count before registration completes, not necessarily drift.",
                            fix = "Forcing AppContributorRegistration.reset()+ensureRegistered() and capture health check.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                checkCaptureThread()
            }

            val assistEnabled = try { com.assistant.SmartAssistRepository.enabled() } catch (_: Throwable) { false }
            if (assistEnabled && engines >= com.assistant.AppContributorRegistration.EXPECTED_CONTRIBUTOR_COUNT /* SPLENDOR_P9_RSE_39A */ && cycles == 0L && ageMs > 10_000L && shouldLog("COLLECT_ZERO", "engines=$engines cycles=0") /* SPLENDOR_P9_RSE_DEDUP_CZ: no per-second age token -- it defeated the 5-min dedup */) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "COLLECT_ZERO",
                    detected = "GameplayEngineRegistry has $engines contributors but collect() has never incremented collectCycles. RuntimeDecisionLoop/onFrame is not reaching contributor collection.",
                    fix = "Trigger capture restart check. Root caller trace required for permanent fix.",
                    severity = "CRITICAL"
                ))
                checkCaptureThread()
            }

            if (delta == 0L && cycles > 100L && engines >= 1 &&
                shouldLog("COLLECT_STALL", "stall_at=$cycles")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "COLLECT_STALL",
                    detected = "GameplayEngineRegistry.collect() stalled — 0 new collect cycles " +
                        "since the previous health check (total cycles so far: $cycles, " +
                        "untrustedSkipped: ${GameplayEngineRegistry.untrustedSkippedCount()}). " +
                        "$engines contributors registered but idle this interval. " +
                        "No progress since the last check; ImageReader death is a hypothesis, not verified. Decision exits: ${decisionExitCounters()}",
                    fix = "Triggering capture restart check via checkCaptureThread().",
                    severity = "CRITICAL"
                ))
                RuntimeLogger.log("AGENT CRITICAL COLLECT_STALL at cycles=$cycles — triggering capture check. Decision exits: ${decisionExitCounters()}", "AGENT")
                checkCaptureThread()
            }

            if (engines < com.assistant.AppContributorRegistration.EXPECTED_CONTRIBUTOR_COUNT && warmed && shouldLog("REGISTRY_GAP", "engines=$engines")) { /* SPLENDOR_P9_RSE_39B */
                // Letter A item 25: AppContributorRegistration.retry() /
                // registrationState() were zero-caller. The gap branch is
                // their live caller: when registration itself reports
                // FAILED or PARTIAL, retry it (retry() no-ops otherwise).
                try { if (com.assistant.AppContributorRegistration.registrationState() == com.assistant.RegistrationState.FAILED || com.assistant.AppContributorRegistration.registrationState() == com.assistant.RegistrationState.PARTIAL) com.assistant.AppContributorRegistration.retry() } catch (_: Throwable) {}
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "REGISTRY_GAP",
                    detected = "Only $engines/${com.assistant.AppContributorRegistration.EXPECTED_CONTRIBUTOR_COUNT} contributors registered. Missing ${com.assistant.AppContributorRegistration.EXPECTED_CONTRIBUTOR_COUNT - engines}. " + /* SPLENDOR_P9_RSE_39C */
                        "Collect cycles since last health check: $delta (0 = no cycles since the previous check, " +
                        "not proof of a frozen collector). " +
                        "Registration is asynchronous from the image-frame path; recheck after registration completes.",
                    fix = "Retried registration via AppContributorRegistration.retry() when registrationState() is FAILED/PARTIAL; " +
                        "otherwise NONE in-memory — registration runs asynchronously from the image-frame path. " +
                        "Check if G2 (capture) + G1 (accessibility) gates both reached.",
                    severity = "CRITICAL"
                ))
            }
            // SPLENDOR_V18_CONTRIB_HEALTH_END
        } catch (t: Throwable) { try { com.assistant.diagnostic.RuntimeLogger.log("SelfHeal checkContributors failed: " + t.javaClass.simpleName + ": " + t.message, "AGENT") } catch (_: Throwable) {} }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BATTERY — warn when low
    // ─────────────────────────────────────────────────────────────────────
    private fun checkBattery() {
        try {
            // Bus snapshot() rewiring (batch 5): one consistent
            // generation — the old code read batteryLevel and
            // batteryCharging via separate getters, so a publish between
            // the two reads could pair a level from one generation with
            // a charging flag from the next.
            val snap = AdapterSignalBus.snapshot()
            val level = snap.batteryLevel
            val charging = snap.batteryCharging
            // Battery range mismatch (batch 5): the old text claimed the
            // 50% gesture reduction for the whole 1..20% band, but
            // SpeedCompensationContributor only reduces at batteryCritical
            // (0..14% and not charging) — at 15..20% gestures run at full
            // duration. The claim now appears only when it is true.
            val critical = level in 0..14 && !charging
            if (level in 1..20 && !charging && shouldLog("BATTERY_LOW", "level=$level")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "BATTERY_LOW",
                    detected = "Battery at ${level}% and not charging. " +
                        (if (critical) "SpeedCompensationContributor reduces gesture duration to 50% of normal. "
                        else "No gesture reduction yet — SpeedCompensationContributor only reduces at 14% or below. ") +
                        "Charging=false means CPU governor may throttle A75 cores.",
                    fix = "OPERATIONAL — reduced gestures is intentional at low battery. " +
                        "Plug in charger for full engine performance.",
                    severity = "WARNING"
                ))
            }
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Deduplication — same category can only log once per DEDUP_MIN_MS
    // ─────────────────────────────────────────────────────────────────────
    @Synchronized
    @Suppress("UNUSED_PARAMETER") // P11: `detail` kept for the 17 call sites; no longer affects the dedup decision
    private fun shouldLog(category: String, detail: String): Boolean {
        // SPLENDOR_P11_RSE_DEDUP: purely per-category time-based (max 1 per
        // 5 min per category, as documented at DEDUP_MIN_MS). The old
        // `|| lastHash != currentHash` clause let ANY detail change re-log
        // immediately, defeating the dedup: LOOP_FROZEN fired 8x and
        // COLLECT_STALL 7x inside 6.4 min in the 2026-11-12 log. `detail` is
        // kept as a parameter (all 17 call sites pass it) but no longer
        // affects the decision; it is hashed by nothing and printed nowhere.
        val now = System.currentTimeMillis()
        val lastMs = lastLoggedMs[category] ?: 0L
        if (now - lastMs > DEDUP_MIN_MS) {
            lastLoggedMs[category] = now
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
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
        try { RuntimeLogger.log("CODE_FIX_NEEDED: $issueId — see HealLog", "AGENT") } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // File + memory logging
    // ─────────────────────────────────────────────────────────────────────
    private fun record(ev: HealEvent) {
        // SPLENDOR_P12_T4_SOURCE: writeToFile() used to call currentLocation()
        // itself, so every HealEvent SOURCE pointed at the writer, never the
        // detecting check. Capture the call site here, where the first
        // surviving frame is the check that called record().
        val sourced = if (ev.source.isEmpty()) ev.copy(source = CrashForensics.currentLocation()) else ev
        healEvents.add(sourced)
        while (healEvents.size > 100) try { healEvents.removeAt(0) } catch (_: Throwable) {}
        writeToFile(sourced)
        try {
            // F37-D11: the old .take(120) cut every CRITICAL line
            // mid-word (COLLECT_STALL "80" for 800, "43" for 4378). The
            // HealLog stores the full text; the mirror must too.
            RuntimeLogger.log("[${sourced.severity}] ${sourced.category}: ${sourced.detected}", "AGENT")
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
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
                w.write("  SOURCE: ${ev.source}\n")
                w.write("  DETECTED: ${ev.detected}\n")
                w.write("  FIX:      ${ev.fix}\n\n")
            }
        } catch (t: Throwable) { android.util.Log.e("RuntimeSelfHealEngine", "suppressed error in self-heal check", t) }
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

/* ========
RuntimeTuningPanel
======== */
