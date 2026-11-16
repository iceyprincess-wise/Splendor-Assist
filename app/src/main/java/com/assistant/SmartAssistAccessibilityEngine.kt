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
        @Volatile
        private var dispatchingSource: com.assistant.execution.ExecutionSource? = null
        private const val DISPATCH_LATCH_TIMEOUT_MS = 250L
        private const val LATCH_STUCK_GRACE_MS = 500L // grace after first stuck report before force-release

        private fun latchStuck(): Boolean =
            isDispatching &&
                System.currentTimeMillis() - dispatchStartedMs > DISPATCH_LATCH_TIMEOUT_MS

        @Volatile
        private var serverTickRateMs = 16.6667f 
        
        // UNRESTRICTED LETHAL CAP: Raised to 250ms to allow long, authoritative control holds.
        private const val MAX_SAFE_DURATION_MS = 250L     
        private const val NOISE_AMPLITUDE_PX = 1.0f     // Dropped from 3.85f to prevent trajectory deviations
        // SPLENDOR_POLL_FIX: Aligned poll rate from aggressive 2ms wakeup to 16ms frame boundary
        private const val BUS_POLL_RATE_MS = 9L // bounded 8.33ms event-wake backstop
        private const val DISPATCH_RATE_WINDOW_MS = 10_000L
    }

    @Volatile private var currentActionId: String? = null
    private var reportedStallActionId: String? = null
    @Volatile private var latchStuckSinceMs = 0L
    @Synchronized private fun completeDispatch(actionId: String) {
        if (currentActionId == actionId) releaseDispatch()
    }
    @Synchronized private fun releaseDispatch() {
        currentActionId?.let { CentralExecutionBus.complete(it) }
        currentActionId = null
        reportedStallActionId = null
        latchStuckSinceMs = 0L
        isDispatching = false
        dispatchingSource = null
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread

    private val windowDispatches = AtomicLong(0L)
    @Volatile private var windowStartMs = 0L

    /**
     * UNCOMPRESSED TICK SYNC: Ensures synchronization never reduces the necessary execution time
     * requested by our lethal engines.
     */
    private fun synchronizeToTickRate(targetDuration: Long): Long {
        if (targetDuration <= 0L) return serverTickRateMs.roundToLong()
        // Do not force rigid division downscales if target duration is intentionally elongated.
        return max(8L, min(targetDuration, MAX_SAFE_DURATION_MS))
    }

    private fun generatePrecisionPath(startX: Float, startY: Float, endX: Float, endY: Float): Path {
        val path = Path()
        // Direct assignment without compounding noise variations twice.
        val safeStartX = startX.coerceAtLeast(0f)
        val safeStartY = startY.coerceAtLeast(0f)
        path.moveTo(safeStartX, safeStartY)

        if (startX == endX && startY == endY) {
            path.lineTo(safeStartX, safeStartY)
        } else {
            val safeEndX = endX.coerceAtLeast(0f)
            val safeEndY = endY.coerceAtLeast(0f)
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
        if (!SmartAssistRepository.enabled()) {
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

        val category = request.actionClass ?: if (request.source == ExecutionSource.GOALKEEPER) com.assistant.runtime.ActionClass.KEEPER else null
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
        dispatchingSource = request.source

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
                screenEndY
            )
            val syncedDuration = synchronizeToTickRate(request.duration)

            val stroke = GestureDescription.StrokeDescription(
                optimizedPath,
                0L,
                syncedDuration
            )
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            RuntimeLogger.execution(
                "DIRECT_DISPATCH",
                "actionId=${request.actionId} source=${request.source} phase=${request.phase} duration=$syncedDuration start=(${request.startX},${request.startY}) end=(${request.endX},${request.endY})"
            )

            FrameAssembler.current()?.takeIf { it.trusted }?.let { frame ->
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
                dispatchingSource = null
            } else {
                recordDispatchForRate()
                ControlMappingTrainer.recordDispatch(request.phase, request.duration)
            }

            accepted
        } catch (e: Exception) {
            releaseDispatch()
            dispatchingSource = null
            RuntimeLogger.log(
                "Direct dispatch vector crash: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    private val busRunnable = object : Runnable {
        override fun run() {
            synchronized(this@SmartAssistAccessibilityEngine) {
            try {
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

                if (com.assistant.execution.UiExecutionGate.blocked() || !SmartAssistRepository.enabled()) {
                    busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                    return
                }

                if (isDispatching) {
                    val headSource = CentralExecutionBus.peekSource()
                    val headPriority = if (headSource != null) HybridExecutionTerminal.priority(headSource) else Int.MIN_VALUE
                    val preempt =
                        headSource != null &&
                            headPriority > dispatchingPriority
                    if (!preempt) {
                        busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                        return
                    }
                    releaseDispatch()
                    dispatchingSource = null
                    RuntimeLogger.execution(
                        "BUS_PREEMPT",
                        "head=$headSource overIntPriority=$dispatchingPriority"
                    )
                }

                val request = CentralExecutionBus.consume()
                if (request != null) {
                    SmartAssistMetrics.recordBusConsumed(request)

                    val accepted = executeDirectRequest(request)
                    if (!accepted) CentralExecutionBus.complete(request.actionId)

                    SmartAssistMetrics.recordBusDispatchResult(request, accepted)
                    if (accepted) {
                        SmartAssistMetrics.executeRequest()
                    }

                    RuntimeLogger.execution(
                        "BUS_DISPATCH",
                        "actionId=${request.actionId} source=${request.source} phase=${request.phase} accepted=$accepted start=(${request.startX},${request.startY}) end=(${request.endX},${request.endY})"
                    )
                }
            } catch (e: Exception) {
                RuntimeLogger.log("Bus execution constraint violation: ${e.message}", "SMART_ASSIST")
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

        dispatcher = ActiveGestureController(this)
        globalInstance = this
        // Wire AsynchronousGestureQueue so its fallback dispatch path is live.
        AsynchronousGestureQueue.bindService(this)
        AccessibilitySurvivalEngine.connected()

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

        val syncedDuration = synchronizeToTickRate(50L)
        if (::dispatcher.isInitialized) {
            dispatcher.injectWinningVector(
                (x1),
                (y1),
                (x2),
                (y2),
                syncedDuration
            )
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
    private fun isNonBlockingForegroundPackage(pkg: String): Boolean {
        if (pkg.equals(packageName, ignoreCase = true)) return true
        if (pkg.equals("com.android.systemui", ignoreCase = true)) return true
        if (pkg.equals("miui.systemui.plugin", ignoreCase = true)) return true
        return try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.inputMethodList?.any { it.packageName.equals(pkg, ignoreCase = true) } == true
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

    private fun stopExecutionLoop() {
        if (::busHandler.isInitialized) {
            busHandler.removeCallbacks(busRunnable)
        }
        if (::busThread.isInitialized) {
            busThread.quitSafely()
        }
        releaseDispatch()
        dispatchingSource = null
        if (globalInstance === this) {
            globalInstance = null
        }
        RuntimeCoordinator.reportAccessibilityLost()
        try { com.assistant.events.SystemEventHub.emit("accessibility-lost") } catch (_: Throwable) {}
    }

    override fun onInterrupt() {
        stopExecutionLoop()
        AccessibilitySurvivalEngine.interrupted()
        AccessibilitySurvivalEngine.getInstance(this).release()
        RuntimeLogger.log(
            "SmartAssistAccessibilityEngine interrupted",
            "SMART_ASSIST"
        )
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopExecutionLoop()
        AccessibilitySurvivalEngine.getInstance(this).release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopExecutionLoop()
        AccessibilitySurvivalEngine.getInstance(this).release()
        super.onDestroy()
    }
}
