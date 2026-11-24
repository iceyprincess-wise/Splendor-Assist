package com.assistant

// F49_PATCH_V1

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.AccessibilitySurvivalEngine

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class SmartAssistAccessibilityEngine : AccessibilityService() {

    companion object {
        @Volatile
        var globalInstance: SmartAssistAccessibilityEngine? = null
        @Volatile
        var isDispatching = false

        @Volatile
        private var dispatchStartedMs = 0L
        @Volatile
        private var dispatchingPriority = Int.MIN_VALUE
        // No68-H-D2: dispatchingSource removed — written on every dispatch,
        // never read anywhere (write-only field).
        private const val DISPATCH_LATCH_TIMEOUT_MS = 250L
        // Latch/GK budget: the stuck-grace force-release must fire INSIDE
        // the 300 ms GK stale budget (CentralExecutionBus.maxAgeMs), or a
        // queued GK save expires before the latch releases. Timeout 250 ms
        // + grace 25 ms = 275 ms nominal (~293 ms worst case with the
        // 9 ms poll granularity), still inside 300 ms.
        private const val LATCH_STUCK_GRACE_MS = 25L // grace after first stuck report before force-release

        private fun latchStuck(): Boolean =
            isDispatching &&
                System.currentTimeMillis() - dispatchStartedMs > DISPATCH_LATCH_TIMEOUT_MS

        @Volatile
        private var serverTickRateMs = 16.6667f 
        
        // UNRESTRICTED LETHAL CAP: Raised to 250ms to allow long, authoritative control holds.
        private const val MAX_SAFE_DURATION_MS = 250L
        // No68-H-D1: NOISE_AMPLITUDE_PX (1.0f) removed — declared, never read.
        // SPLENDOR_POLL_FIX: Aligned poll rate from aggressive 2ms wakeup to 16ms frame boundary
        // No68-H-D10: 9L, not 8.33 — a Long-ms poll cannot express 8.33ms;
        // 9ms is the ceiling of the 8.33ms event-wake backstop.
        private const val BUS_POLL_RATE_MS = 9L // ceil of the 8.33ms event-wake backstop
        private const val DISPATCH_RATE_WINDOW_MS = 10_000L
    }

    @Volatile private var currentActionId: String? = null
    private var reportedStallActionId: String? = null
    // F38-D1: bus-loop liveness. The 2026-11-16 live session's dispatch
    // consumer died at 22:09:48 with no trace: the busRunnable catch was
    // Exception-only (an Error skipped the re-post and killed the loop
    // silently) and the gate/enabled idle branch returned silently.
    // lastBusHeartbeatMs paces the BUS_ALIVE line (1/min);
    // lastBusIdleReason logs each idle reason once, and once when it clears.
    @Volatile private var lastBusHeartbeatMs = 0L
    @Volatile private var lastBusIdleReason: String? = null
    // Batch 6b (v219 item i): BUS_PREEMPT used to log on EVERY 9 ms
    // poll while a higher-priority head waited for the live stroke —
    // 63 identical lines in bursts of 5 in the v219 session. One log
    // per wait episode (key = head + priorities + live action) is the
    // whole signal; repeats add nothing. Null = not currently waiting.
    @Volatile private var lastPreemptKey: String? = null
    @Volatile private var latchStuckSinceMs = 0L
    // N5: first-occurrence witness for the H-D5 physical clamp in
    // generatePrecisionPath (below) — the f57 clamp was silent.
    @Volatile private var pathClampLogged = false
    @Synchronized private fun completeDispatch(actionId: String) {
        if (currentActionId == actionId) releaseDispatch()
    }
    @Synchronized private fun releaseDispatch() {
        currentActionId?.let { CentralExecutionBus.complete(it) }
        currentActionId = null
        reportedStallActionId = null
        latchStuckSinceMs = 0L
        isDispatching = false
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread
    // B3: the bus consumer's liveness, tracked explicitly — a quit
    // HandlerThread is dead even though the service object may still
    // be alive (onInterrupt), and the recovery engine reads this.
    @Volatile private var busConsumerAlive = false

    private val windowDispatches = AtomicLong(0L)
    @Volatile private var windowStartMs = 0L

    /**
     * No68-H-D3: this used to be called synchronizeToTickRate, but it never
     * synchronised anything to the tick — it only clamps to [8, 250]ms (and
     * substitutes one tick for non-positive durations). Renamed to what it
     * does; behaviour preserved. Whether durations should quantise UP to a
     * tick multiple is an open question, not a silent change (QH-17).
     */
    private fun clampDispatchDuration(targetDuration: Long): Long {
        if (targetDuration <= 0L) return serverTickRateMs.roundToLong()
        // Do not force rigid division downscales if target duration is intentionally elongated.
        return max(8L, min(targetDuration, MAX_SAFE_DURATION_MS))
    }

    // No68-H-D5: the old path clamped only the lower bound — an end past
    // the right/bottom edge sailed off-screen. Clamp to the screen bounds
    // when known (CameraProfile source dims); 0 means unknown, and the
    // behaviour is then the old lower-bound-only clamp.
    private fun generatePrecisionPath(startX: Float, startY: Float, endX: Float, endY: Float, screenWidth: Float = 0f, screenHeight: Float = 0f): Path {
        val path = Path()
        fun clampX(v: Float): Float = if (screenWidth > 0f) v.coerceIn(0f, screenWidth) else v.coerceAtLeast(0f)
        fun clampY(v: Float): Float = if (screenHeight > 0f) v.coerceIn(0f, screenHeight) else v.coerceAtLeast(0f)
        val safeStartX = clampX(startX)
        val safeStartY = clampY(startY)
        path.moveTo(safeStartX, safeStartY)

        if (startX == endX && startY == endY) {
            path.lineTo(safeStartX, safeStartY)
        } else {
            val safeEndX = clampX(endX)
            val safeEndY = clampY(endY)
            // N5: the H-D5 clamp existed in the f57 build but was silent —
            // the 2026-11-18 logs could not prove the clamped stroke
            // landed where intended. Log the first clamp, once per session.
            if (!pathClampLogged && (safeEndX != endX || safeEndY != endY || safeStartX != startX || safeStartY != startY)) {
                pathClampLogged = true
                try { com.assistant.diagnostic.RuntimeLogger.log("PATH_CLAMPED origStart=($startX,$startY) origEnd=($endX,$endY) clampedEnd=($safeEndX,$safeEndY) screen=${screenWidth.toInt()}x${screenHeight.toInt()}", "SMART_ASSIST") } catch (_: Throwable) {}
            }
            path.lineTo(safeEndX, safeEndY)
        }
        return path
    }

    private fun recordDispatchForRate() {
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = now
        windowDispatches.incrementAndGet()
        val elapsed = now - windowStartMs
        if (elapsed >= DISPATCH_RATE_WINDOW_MS) {
            val count = windowDispatches.getAndSet(0L)
            windowStartMs = now
            val perSecond = count * 1000f / elapsed.coerceAtLeast(1L)
            RuntimeLogger.execution(
                "DISPATCH_RATE",
                "actions=$count window=${elapsed}ms rate=${"%.1f".format(perSecond)}/s"
            )
        }
    }

    // Keeper fix (Ed, 2026-11-23): the keeper/interception emergency
    // lane — the same definition ContributionRegistry.isEmergencySource
    // uses (GOALKEEPER/INTERCEPTION, the bus high ring). v231 showed
    // emergency offers accepted=412 into the bus but Execution
    // accepted=0, because the dispatch gates refused everything while
    // Smart Assist was OFF. The emergency lane dispatches even when SA
    // is OFF; the RDL flood (SMART_ASSIST) and STUTTER still require
    // SA ON. The touch gate in GestureExecutionAuthority.execute() is
    // unchanged: Bypass OFF still rejects unless the user is touching.
    private fun isEmergencySource(source: ExecutionSource): Boolean =
        source == ExecutionSource.GOALKEEPER ||
            source == ExecutionSource.INTERCEPTION

    // Keeper fix (Ed, 2026-11-23): true only when the queue head is an
    // emergency save waiting to dispatch. peekSource() returns the
    // highest-priority non-stale head (high ring first), so a GK/INT
    // head means an emergency is pending; anything else (SMART_ASSIST,
    // STUTTER, empty) means no emergency is waiting.
    private fun hasEmergencyHead(): Boolean {
        val head = CentralExecutionBus.peekSource()
        return head != null && isEmergencySource(head)
    }

    @Synchronized fun executeDirectRequest(request: com.assistant.execution.ExecutionRequest): Boolean {
        // FINAL EXECUTION GATE
        // Never turn a stale bus request into an Accessibility gesture
        // while the game is not foreground.
        if (com.assistant.execution.UiExecutionGate.blocked()) {
            // SPLENDOR_P11_D12: was mislabelled "SmartAssist disabled" when
            // the cause was the UI gate, not the SmartAssist toggle.
            RuntimeLogger.log(
                "Direct dispatch blocked: UiExecutionGate blocked reason=${com.assistant.execution.UiExecutionGate.reason}",
                "SMART_ASSIST"
            )
            return false
        }
        // Keeper fix (Ed, 2026-11-23): the emergency lane (GOALKEEPER /
        // INTERCEPTION) dispatches even when Smart Assist is OFF — the
        // v231 evidence is emergency offers accepted=412 into the bus
        // with Execution accepted=0. SMART_ASSIST (RDL flood, OCR pipe,
        // legacy paths) and STUTTER still require SA ON.
        if (!SmartAssistRepository.enabled() && !isEmergencySource(request.source)) {
            RuntimeLogger.log(
                "Direct dispatch blocked: SmartAssist disabled",
                "SMART_ASSIST"
            )
            return false
        }

        if (!VisionTrust.isGameForeground()) {
            RuntimeLogger.log(
                "Direct dispatch blocked: game foreground is false",
                "SMART_ASSIST"
            )
            return false
        }

        // SPLENDOR_P12_A2_PACKAGE: the package-deny gate was only checked
        // downstream in GestureExecutionAuthority, so attempts passed every
        // pre-check, logged DIRECT_DISPATCH, then died on package-deny.
        // Pre-check it here (the authority's own check stays: TOCTOU).
        if (!VisionTrust.isExecutionPackageAllowed()) {
            RuntimeLogger.log(
                "Direct dispatch blocked: package deny pkg=${VisionTrust.diagnostics()}",
                "SMART_ASSIST"
            )
            return false
        }

        // INT cooling: INTERCEPTION offers carry a null actionClass (like
        // GK), so without a fallback they skip cooling entirely. Map them
        // to DEFEND — the same fallback ActionOutcomeVerifier uses for an
        // INTERCEPTION source (ActionOutcomeVerifier.kt:60) — so INT
        // dispatches get the same 150 ms cooling window as GK.
        val category = request.actionClass
            ?: if (request.source == ExecutionSource.GOALKEEPER) com.assistant.runtime.ActionClass.KEEPER
            else if (request.source == ExecutionSource.INTERCEPTION) com.assistant.runtime.ActionClass.DEFEND
            else null
        if (category != null && ActionOutcomeVerifier.cooling(category)) {
            // SPLENDOR_P11_D02: was a silent early return — the only trace
            // of a cooling-dropped dispatch was the absence of a
            // DIRECT_DISPATCH line.
            RuntimeLogger.execution("BUS_DROPPED", "reason=cooling actionClass=$category actionId=${request.actionId}")
            RuntimeLogger.log("Direct dispatch dropped: cooling actionClass=$category actionId=${request.actionId}", "SMART_ASSIST")
            return false
        }
        if (isDispatching) {
            // SPLENDOR_P11_D02: was a silent early return — a dispatch
            // dropped while a stroke is live left no trace.
            RuntimeLogger.execution("BUS_DROPPED", "reason=latch-held heldBy=$currentActionId actionId=${request.actionId}")
            RuntimeLogger.log("Direct dispatch dropped: dispatch latch held by actionId=$currentActionId actionId=${request.actionId}", "SMART_ASSIST")
            return false // Completion callbacks own the live stroke, not a duration estimate.
        }

        currentActionId = request.actionId
        isDispatching = true
        dispatchStartedMs = System.currentTimeMillis()
        dispatchingPriority = HybridExecutionTerminal.priority(request.source)

        return try {
            // SPLENDOR_CAPTURE_TO_SCREEN_V1 - every ExecutionRequest is built in
            // the capture frame's space (frame.ballX/ballY and the capture-sized
            // CameraProfile bounds), but dispatchGesture consumes real screen
            // pixels. Converting at this single choke point stops every gesture
            // from landing at ~40% of its intended screen position.
            val screenStartX = com.assistant.vision.CameraProfile.captureToScreenX(request.startX)
            val screenStartY = com.assistant.vision.CameraProfile.captureToScreenY(request.startY)
            val screenEndX = com.assistant.vision.CameraProfile.captureToScreenX(request.endX)
            val screenEndY = com.assistant.vision.CameraProfile.captureToScreenY(request.endY)
            val optimizedPath = generatePrecisionPath(
                screenStartX,
                screenStartY,
                screenEndX,
                screenEndY,
                com.assistant.vision.CameraProfile.sourceWidth.toFloat(),
                com.assistant.vision.CameraProfile.sourceHeight.toFloat()
            )
            val syncedDuration = clampDispatchDuration(request.duration)

            val stroke = GestureDescription.StrokeDescription(
                optimizedPath,
                0L,
                syncedDuration
            )
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            // No68-H-D4: the old line logged the CAPTURE coords while the
            // gesture uses the SCREEN coords — the log lied about where the
            // stroke lands. Log the dispatched coordinates.
            RuntimeLogger.execution(
                "DIRECT_DISPATCH",
                "actionId=${request.actionId} source=${request.source} phase=${request.phase} duration=$syncedDuration start=($screenStartX,$screenStartY) end=($screenEndX,$screenEndY)"
            )

            // Queue item 13: the end-to-end leg's consume-frame hook.
            // The trusted current frame is hoisted so the accepted branch
            // below can record capture→execute latency for EVERY
            // bus-consumed dispatch (RDL, keeper emergency, AGC legacy,
            // OCR pipe, zero-latency) — SAAE:390 is the sole production
            // consumer. Samples are consume-frame-based: the frame at
            // consume time is not necessarily the frame that produced
            // the request, so e2e understates the true capture→dispatch
            // (see the ExecutionRequest.capturedAtNanos note in TRACE).
            val dispatchFrame = FrameAssembler.current()?.takeIf { it.trusted }
            dispatchFrame?.let { frame ->
                ActionOutcomeVerifier.recordDispatch(request, frame)
            }
            val accepted = GestureExecutionAuthority.execute(
                this,
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        RuntimeLogger.log("Direct dispatch gesture completed actionId=${request.actionId}", "SMART_ASSIST")
                        completeDispatch(request.actionId)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        RuntimeLogger.log("Direct dispatch gesture cancelled actionId=${request.actionId}", "SMART_ASSIST")
                        completeDispatch(request.actionId)
                    }
                },
                null,
                origin = "bus:${request.source}",
                actionIdParam = request.actionId,
                busAcceptedParam = request.busAccepted,
                busConsumedParam = request.busConsumed
            )

            if (!accepted) {
                ActionOutcomeVerifier.cancelDispatch(request.actionId)
                releaseDispatch()
            } else {
                recordDispatchForRate()
                // Queue item 13: end-to-end latency — the ONLY record site
                // now (RuntimeDecisionLoop's route-accept call is deleted
                // in the same change, so each executed gesture records
                // exactly once). e2e is capture→actual-gesture-dispatch
                // (includes bus queue wait), the truer end-to-end.
                dispatchFrame?.let {
                    PipelineLatencyMonitor.recordDispatch(it.capturedAtNanos, System.nanoTime())
                }
                ControlMappingTrainer.recordDispatch(request.phase, request.duration)
            }

            accepted
        } catch (t: Throwable) {
            // No68-H-D7: the catch was Exception-only, so an Error (OOM,
            // linkage, stack overflow in the gesture build) escaped with
            // the latch held until the stuck-grace force-release — the same
            // Exception-only shape F38-D1 fixed in the bus loop below.
            releaseDispatch()
            RuntimeLogger.log(
                "Direct dispatch vector crash: ${t.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    private val busRunnable = object : Runnable {
        override fun run() {
            synchronized(this@SmartAssistAccessibilityEngine) {
            try {
                // F38-D1: heartbeat — one line per minute proving the bus
                // loop is alive, with live bus counts. A dead loop leaves
                // no trace today; the heartbeat makes its absence provable.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastBusHeartbeatMs >= 60_000L) {
                    lastBusHeartbeatMs = nowMs
                    val busStats = CentralExecutionBus.statistics.value
                    RuntimeLogger.log("BUS_ALIVE pending=${CentralExecutionBus.pendingCount()} accepted=${busStats.acceptedCount} consumed=${busStats.consumedCount} reservationDrops=${busStats.reservationDrops}", "SMART_ASSIST")
                }
                if (latchStuck()) {
                    if (reportedStallActionId != currentActionId) {
                        reportedStallActionId = currentActionId
                        latchStuckSinceMs = System.currentTimeMillis()
                        RuntimeLogger.log("dispatch callback overdue actionId=$currentActionId; retaining active stroke until callback or service recovery", "SMART_ASSIST")
                    } else if (System.currentTimeMillis() - latchStuckSinceMs > LATCH_STUCK_GRACE_MS) {
                        RuntimeLogger.log("dispatch callback lost; force-releasing stuck dispatch $currentActionId", "SMART_ASSIST")
                        releaseDispatch()
                    }
                }

                // F38-D1: the idle branch used to return silently, so a
                // loop parked here looked identical to a dead loop. Log
                // once per reason, and once when it clears.
                // Keeper fix (Ed, 2026-11-23): when Smart Assist is OFF
                // the loop still runs to serve a waiting emergency save
                // (GOALKEEPER/INTERCEPTION head, see hasEmergencyHead)
                // — v231 showed emergency offers accepted=412 into the
                // bus while this branch parked the loop. SMART_ASSIST /
                // STUTTER heads still park here when SA is OFF, and the
                // UI gate above still idles everything.
                val busIdleReason = when {
                    com.assistant.execution.UiExecutionGate.blocked() -> "uiGate:${com.assistant.execution.UiExecutionGate.reason}"
                    !SmartAssistRepository.enabled() && !hasEmergencyHead() -> "smartAssistDisabled"
                    else -> null
                }
                if (busIdleReason != null) {
                    if (busIdleReason != lastBusIdleReason) {
                        lastBusIdleReason = busIdleReason
                        RuntimeLogger.log("Bus loop idle: $busIdleReason", "SMART_ASSIST")
                    }
                    busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                    return
                }
                if (lastBusIdleReason != null) {
                    lastBusIdleReason = null
                    RuntimeLogger.log("Bus loop idle cleared - resuming", "SMART_ASSIST")
                }

                if (isDispatching) {
                    val headSource = CentralExecutionBus.peekSource()
                    val headPriority = if (headSource != null) HybridExecutionTerminal.priority(headSource) else Int.MIN_VALUE
                    val preempt =
                        headSource != null &&
                            headPriority > dispatchingPriority
                    if (!preempt) {
                        // Batch 6b: not waiting — clear the episode key
                        // so a later head for this stroke logs as new.
                        lastPreemptKey = null
                        busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                        return
                    }
                    // No68-H-D6: the old code released the latch here, which
                    // abandoned the live stroke (its completion callback
                    // became a no-op via the actionId check) and let the
                    // head request dispatch while the old stroke was still
                    // running on screen — either losing the head (the OS
                    // refuses overlapping gestures) or overlapping two
                    // strokes. Wait instead: the live stroke is at most
                    // 250ms and the head (GK/INT, 300ms maxAge) stays
                    // queued; the latch-stuck guard above force-releases a
                    // lost callback, so this wait is bounded.
                    // Batch 6b: log once per wait episode (see
                    // lastPreemptKey) — the 9 ms poll used to repeat this
                    // identical line for the whole wait.
                    val preemptKey = "$headSource/$headPriority/$dispatchingPriority/$currentActionId"
                    if (preemptKey != lastPreemptKey) {
                        lastPreemptKey = preemptKey
                        RuntimeLogger.execution(
                            "BUS_PREEMPT",
                            "head=$headSource headPriority=$headPriority currentPriority=$dispatchingPriority waitingForStroke"
                        )
                    }
                    busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                    return
                }
                // Not waiting on a preemption any more — a later wait is
                // a new episode and logs again (batch 6b).
                lastPreemptKey = null

                // G2 (item 20): expire submissions never dispatched, once per
                // 9ms poll, before consuming — bus queue pressure shows here
                // first (120ms), before the bus's own 250-350ms staleness drop.
                GestureTimingFeedbackEngine.checkExpiry()
                val request = CentralExecutionBus.consume()
                if (request != null) {
                    SmartAssistMetrics.recordBusConsumed(request)
                    // G2 (item 20): timing-feedback dispatch leg — submit to
                    // consume latency for this actionId.
                    GestureTimingFeedbackEngine.recordDispatch(request.actionId)

                    val accepted = executeDirectRequest(request)
                    // Bus-reservation release (Ed, 2026-11-23): the
                    // reservation for (source, phase) releases as soon as
                    // the dispatch decision is made — accepted or not —
                    // instead of being held until the gesture's completion
                    // callback fires. That callback can be lost/late (up
                    // to ~750 ms on the stuck-grace path), and while it was
                    // held every same-phase submit dropped as
                    // "reservation" (7,326 drops vs 715 dispatches in the
                    // 2026-11-23 session). The dispatch LATCH
                    // (isDispatching, released by the callback or the stuck
                    // guard) still serializes live strokes — this only
                    // stops the bus reservation from being a second,
                    // longer-lived lock. complete() is by actionId value,
                    // so it can never release another request's
                    // reservation; the callback's later releaseDispatch()
                    // is a safe no-op for the bus.
                    CentralExecutionBus.complete(request.actionId)

                    SmartAssistMetrics.recordBusDispatchResult(request, accepted)
                    if (accepted) {
                        SmartAssistMetrics.executeRequest()
                    }

                    RuntimeLogger.execution(
                        "BUS_DISPATCH",
                        "actionId=${request.actionId} source=${request.source} phase=${request.phase} accepted=$accepted start=(${request.startX},${request.startY}) end=(${request.endX},${request.endY})"
                    )
                }
            } catch (e: Throwable) {
                // F38-D1: the catch was Exception-only, so an Error
                // (OOM, stack overflow, linkage) escaped, skipped the
                // re-post below, and killed the bus loop silently — the
                // 2026-11-16 session's 22:09:48 consumer death matches
                // this shape exactly. Log the Error; the re-post below
                // keeps the loop alive.
                RuntimeLogger.log("Bus execution FATAL ${e::class.simpleName}: ${e.message}", "SMART_ASSIST")
            }

            busHandler.postDelayed(this, BUS_POLL_RATE_MS)
            }
        }
    }

    override fun onServiceConnected() {
        try {
            val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val display: android.view.Display? = windowManager.defaultDisplay
            val refreshRate = display?.refreshRate ?: 60.0f
            serverTickRateMs = if (refreshRate > 0f) 1000.0f / refreshRate else 16.6667f
        } catch (_: Throwable) {
            serverTickRateMs = 16.6667f
        }

        AccessibilitySurvivalEngine.getInstance(this).protect()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        busThread = HandlerThread("SmartAssistBus", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        busHandler = Handler(busThread.looper)
        busConsumerAlive = true

        dispatcher = ActiveGestureController(this)
        globalInstance = this
        AccessibilitySurvivalEngine.connected()

        // No63-D1: feed the accessibility-survival registry — it was dead
        // (zero callers) before this round; DashboardInjector reads its
        // state() into the metrics block.
        com.assistant.survival.AccessibilitySurvivalRegistry.update(
            com.assistant.survival.AccessibilitySurvivalRegistry.STATUS_CONNECTED
        )

        // SPLENDOR_F34_SEED_FG: seed foreground package from rootInActiveWindow on connect.
        // If the game was already running when the service came up, this breaks the
        // cold-start dead lock without waiting for a new window-state-changed event.
        try {
            val activeRoot = rootInActiveWindow
            val seedPkg = activeRoot?.packageName?.toString()
            if (!seedPkg.isNullOrEmpty() && seedPkg != packageName && !seedPkg.contains("systemui", true)) {
                VisionTrust.onForegroundPackage(seedPkg, VisionTrust.GAME_PACKAGES)
                RuntimeLogger.log("Seeded foreground package on connect: $seedPkg", "SMART_ASSIST")
            }
        } catch (_: Throwable) {}
        // F37-D3: the seed above is single-shot — if the service connected
        // while Settings/a transient UI was foreground, the flag stays
        // non-game until a later pesam event arrives. Re-seed once after 5s.
        try {
            busHandler.postDelayed({
                try {
                    val pkg = rootInActiveWindow?.packageName?.toString()
                    if (!pkg.isNullOrEmpty() && pkg != packageName && !pkg.contains("systemui", true)) {
                        VisionTrust.onForegroundPackage(pkg, VisionTrust.GAME_PACKAGES)
                        RuntimeLogger.log("Re-seeded foreground package after connect: $pkg", "SMART_ASSIST")
                    }
                } catch (_: Throwable) {}
            }, 5_000L)
        } catch (_: Throwable) {}

        // G2 (item 20): fresh timing state for this bus-loop session —
        // stale pending submissions from a previous session must not
        // poison the new session's averages or expiry counts.
        GestureTimingFeedbackEngine.reset()
        RuntimeCoordinator.attachExecutionLoop(
            start = { busHandler.post(busRunnable) },
            stop = { stopExecutionLoop() }
        )
        RuntimeCoordinator.reportPermissionsVerified()
        RuntimeCoordinator.reportAccessibilityReady()
        try { com.assistant.events.SystemEventHub.emit("accessibility-connected") } catch (_: Throwable) {}
        RuntimeLogger.log("SmartAssistAccessibilityEngine [OMEGA BUILD] connected BUILD_MARKER=TASKC-PREEMPTIVE-DISPATCH", "SMART_ASSIST")
    }

    fun triggerInstantExecution(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (com.assistant.execution.UiExecutionGate.blocked() || !SmartAssistRepository.enabled()) {
            RuntimeLogger.log("SmartAssist disabled, trigger dropped", "SMART_ASSIST")
            return
        }

        if (!VisionTrust.isGameForeground()) {
            RuntimeLogger.log(
                "Game foreground is false, trigger dropped",
                "SMART_ASSIST"
            )
            return
        }

        val syncedDuration = clampDispatchDuration(50L)
        if (::dispatcher.isInitialized) {
            // No68-H-D9: a throw inside injectWinningVector used to escape
            // to onFrame — contain it here so the trigger fails logged, not
            // lost inside the decision loop.
            try {
                dispatcher.injectWinningVector(
                    (x1),
                    (y1),
                    (x2),
                    (y2),
                    syncedDuration
                )
            } catch (t: Throwable) {
                RuntimeLogger.log("triggerInstantExecution failed: ${t.message}", "SMART_ASSIST")
            }
        }
    }

    // SPLENDOR_P11_D03: system UI, the app's own overlay window and the
    // keyboard are not gameplay apps: their window-state events must not
    // block execution (and wipe the bus queue). Only foreign *apps* block.
    // Provenance (2026-11-12 session, 858 blocks): systemui 139,
    // miui.systemui.plugin 14, keyboard (com.touchtype.swiftkey) 17, own
    // overlay (com.assistant.overlay) 8 — all side-effects of the missing
    // allowlist, none of them a foreign app. WhatsApp (434) and the other
    // foreign apps still block, as designed.
    // No68-H-D8: the IME list is an IPC that used to run on EVERY window
    // state event. Cache the package set; the IME set effectively never
    // changes while the service runs.
    @Volatile private var cachedImePackages: Set<String>? = null

    private fun isNonBlockingForegroundPackage(pkg: String): Boolean {
        if (pkg.equals(packageName, ignoreCase = true)) return true
        if (pkg.equals("com.android.systemui", ignoreCase = true)) return true
        if (pkg.equals("miui.systemui.plugin", ignoreCase = true)) return true
        return try {
            var imePackages = cachedImePackages
            if (imePackages == null) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imePackages = imm?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
                cachedImePackages = imePackages
            }
            imePackages.any { it.equals(pkg, ignoreCase = true) }
        } catch (_: Throwable) { false }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // SPLENDOR_FG_TRUTH_V1 - feed the REAL foreground package into the
        // vision trust gate. This callback was empty, so VisionTrust.
        // onForegroundPackage() was dead code and the execution gate had no
        // real input. (F37-D3: it is not the only writer — setGameForeground
        // is also written by RuntimeSelfHealEngine and ForegroundGate.)
        // accessibility_service_config.xml already declares
        // typeWindowStateChanged, so these events are delivered.
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                UserTouchState.setTouching(true)
                RuntimeDecisionLoop.invalidateGestureEnvelopeOnManualTouch()
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ->
                UserTouchState.setTouching(false)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                try {
                    // F37-D3: transient overlays (systemui, keyboard) used to
                    // write foregroundIsGame=false on every window change,
                    // then the 2s dwell delayed the flip back. Only genuine
                    // app switches write the trust flag now.
                    if (!isNonBlockingForegroundPackage(pkg))
                        VisionTrust.onForegroundPackage(pkg, VisionTrust.GAME_PACKAGES)
                    if (!VisionTrust.GAME_PACKAGES.any { pkg.equals(it, ignoreCase = true) } && !isNonBlockingForegroundPackage(pkg))
                        com.assistant.execution.UiExecutionGate.block("foreground:$pkg")
                } catch (_: Throwable) {
                }
            }
            else -> Unit
        }
    }

    /**
     * B3 — re-establishes the bus consumer after a G1 loss. This is NOT
     * a bus restart (forbidden): it recreates the dead HandlerThread +
     * Handler, re-attaches the execution loop, and re-reports G1 ready
     * so RuntimeCoordinator's normal G5 path re-enables the bus. Called
     * by AccessibilityGateRecoveryEngine (self-heal cycle). Returns false
     * when the consumer could not be rebuilt (the engine retries).
     */
    @Synchronized fun isBusConsumerAlive(): Boolean {
        if (!busConsumerAlive) return false
        if (!::busThread.isInitialized || !::busHandler.isInitialized) return false
        return busThread.isAlive
    }

    @Synchronized fun restartBusConsumer(): Boolean {
        if (isBusConsumerAlive()) return true
        return try {
            if (::busThread.isInitialized) {
                try { busThread.quitSafely() } catch (_: Throwable) { }
            }
            busThread = HandlerThread("SmartAssistBus", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
            busHandler = Handler(busThread.looper)
            busConsumerAlive = true
            globalInstance = this
            RuntimeCoordinator.attachExecutionLoop(
                start = { busHandler.post(busRunnable) },
                stop = { stopExecutionLoop() }
            )
            // The coordinator re-enables the bus through its normal G5
            // path (bus start + startExecutionLoop post); this method
            // never touches CentralExecutionBus directly.
            RuntimeCoordinator.reportAccessibilityReady()
            true
        } catch (_: Throwable) { false }
    }

    private fun stopExecutionLoop(clearInstance: Boolean = true) {
        busConsumerAlive = false
        if (::busHandler.isInitialized) {
            busHandler.removeCallbacks(busRunnable)
        }
        if (::busThread.isInitialized) {
            busThread.quitSafely()
        }
        releaseDispatch()
        if (clearInstance && globalInstance === this) {
            globalInstance = null
        }
        RuntimeCoordinator.reportAccessibilityLost()
        try { com.assistant.events.SystemEventHub.emit("accessibility-lost") } catch (_: Throwable) {}
    }

    override fun onInterrupt() {
        // N2: name the lifecycle callback — the bare "G1 lost" line cannot
        // distinguish onInterrupt vs onUnbind vs onDestroy.
        RuntimeLogger.log("SmartAssistAccessibilityEngine onInterrupt: stopping execution loop", "SMART_ASSIST")
        // L4 (batch 1): teardown attribution — a system interrupt is an
        // external teardown event for the next shutdown().
        com.assistant.TeardownAttributionEngine.noteInterrupt()
        // B3: an interrupt kills the consumer thread but the service
        // object is still alive — keep globalInstance so the gate
        // recovery engine can find it and rebuild the consumer. (onUnbind
        // / onDestroy still clear it: the service is really gone there.)
        stopExecutionLoop(clearInstance = false)
        AccessibilitySurvivalEngine.interrupted()
        AccessibilitySurvivalEngine.getInstance(this).release()
        RuntimeLogger.log(
            "SmartAssistAccessibilityEngine interrupted",
            "SMART_ASSIST"
        )
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // N2: name the lifecycle callback (see onInterrupt).
        RuntimeLogger.log("SmartAssistAccessibilityEngine onUnbind: stopping execution loop", "SMART_ASSIST")
        // L4 (batch 1): teardown attribution — unbind is the accessibility
        // service going away (toggle off / system unbind).
        com.assistant.TeardownAttributionEngine.noteAccessibilityOff("SmartAssistAccessibilityEngine.onUnbind")
        stopExecutionLoop()
        AccessibilitySurvivalEngine.getInstance(this).release()
        // No63-D1: the service is unbound — the registry ages from here
        // (CONNECTED for 30s, then DEGRADED, then OFFLINE).
        com.assistant.survival.AccessibilitySurvivalRegistry.update(
            com.assistant.survival.AccessibilitySurvivalRegistry.STATUS_DISCONNECTED
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // N2: name the lifecycle callback (see onInterrupt).
        RuntimeLogger.log("SmartAssistAccessibilityEngine onDestroy: stopping execution loop", "SMART_ASSIST")
        // L4 (batch 1): the teardown attribution — the service going away
        // is an external teardown event; the next shutdown() names it.
        com.assistant.TeardownAttributionEngine.noteAccessibilityOff("SmartAssistAccessibilityEngine.onDestroy")
        // L8 (batch 1): the optimizer teardown hook (Q68-41).
        try {
            if (::dispatcher.isInitialized) dispatcher.shutdown()
        } catch (_: Throwable) { }
        stopExecutionLoop()
        AccessibilitySurvivalEngine.getInstance(this).release()
        // No63-D1: same as onUnbind — the service is gone.
        com.assistant.survival.AccessibilitySurvivalRegistry.update(
            com.assistant.survival.AccessibilitySurvivalRegistry.STATUS_DISCONNECTED
        )
        super.onDestroy()
    }
}
