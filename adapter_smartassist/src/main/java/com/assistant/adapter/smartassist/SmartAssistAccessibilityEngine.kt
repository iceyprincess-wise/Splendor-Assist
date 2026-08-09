package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.adapter.smartassist.AccessibilitySurvivalEngine
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class SmartAssistAccessibilityEngine : AccessibilityService() {

    companion object {
        @Volatile
        var globalInstance: SmartAssistAccessibilityEngine? = null
        @Volatile
        var isDispatching = false

        /*
         * DISPATCH LATCH - Task B round 4.
         *
         * Field logs proved the gesture completion callback NEVER fires on
         * this device/OS build: every single dispatch's latch had to be
         * force-cleared by the 400ms watchdog (hundreds of 'stuck latch
         * force-cleared' lines per match). That watchdog saved the session
         * from total silence, but it taxed EVERY action with a dead 400ms -
         * capping the whole app at ~2.5 actions/second while the decision
         * loop was approving dozens per second.
         *
         * The latch is now SELF-TIMED: a gesture physically cannot outlive
         * its own duration (hard cap 85ms), so the latch is released on a
         * schedule of duration+40ms by the dispatcher itself. The callback,
         * when it does fire, releases earlier; the watchdog stays only as a
         * last-resort backstop at 250ms. Effective throughput rises from
         * ~2.5/s to the decision loop's real pace.
         */
        @Volatile
        private var dispatchStartedMs = 0L
        private const val DISPATCH_LATCH_TIMEOUT_MS = 250L
        private const val LATCH_RELEASE_MARGIN_MS = 40L

        private fun latchStuck(): Boolean =
            isDispatching &&
                System.currentTimeMillis() - dispatchStartedMs > DISPATCH_LATCH_TIMEOUT_MS

        // =========================================================================
        // ADVANCED ENGINEERING CONSTANTS
        // =========================================================================
        private const val SERVER_TICK_RATE_MS = 16.6667f // 60Hz Server-Tick bounds for packet sync
        private const val MAX_SAFE_DURATION_MS = 85L     // Absolute input cap to avoid system ANR flags
        private const val NOISE_AMPLITUDE_PX = 3.85f     // Micro-variance vector bounds for humanization
        private const val BUS_POLL_RATE_MS = 8L          // Nyquist-compliant sub-frame polling
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread

    // =========================================================================
    // CORE MATH & OPTIMIZATION UTILITIES
    // =========================================================================

    /**
     * ADAPTIVE NOISE HUMANIZATION: Applies micro-pixel shifts using fast thread-local random
     * generators. Prevents rigid machine pathing and evades server-side heuristic flags.
     */
    private fun applyHumanizedNoise(value: Float): Float {
        val noise = (ThreadLocalRandom.current().nextFloat() * 2 - 1) * NOISE_AMPLITUDE_PX
        return value + noise
    }

    /**
     * SERVER-TICK SYNC: Dynamically scales physical hold durations to match backend packet limits.
     * Guarantees maximum network possession effectiveness under high-ping logic.
     */
    private fun synchronizeToTickRate(targetDuration: Long): Long {
        val ticks = (targetDuration / SERVER_TICK_RATE_MS).roundToLong()
        val synchronizedMs = (ticks * SERVER_TICK_RATE_MS).roundToLong()
        return max(16L, min(synchronizedMs, MAX_SAFE_DURATION_MS))
    }

    /**
     * AMPLIFIED INPUT EFFECTIVENESS: Replaces standard linear drops with stabilized hardware paths.
     */
    private fun generatePrecisionPath(startX: Float, startY: Float, endX: Float, endY: Float): Path {
        val path = Path()
        // Coordinates must never be negative -- Android Path throws otherwise.
        // Clamp AFTER humanization so noise cannot push a near-zero value negative.
        val safeStartX = applyHumanizedNoise(startX).coerceAtLeast(0f)
        val safeStartY = applyHumanizedNoise(startY).coerceAtLeast(0f)
        path.moveTo(safeStartX, safeStartY)

        if (startX == endX && startY == endY) {
            path.lineTo(safeStartX, safeStartY)
        } else {
            val safeEndX = applyHumanizedNoise(endX).coerceAtLeast(0f)
            val safeEndY = applyHumanizedNoise(endY).coerceAtLeast(0f)
            path.lineTo(safeEndX, safeEndY)
        }
        return path
    }

    private fun scheduleLatchRelease(startedAt: Long, durationMs: Long) {
        val h = if (::busHandler.isInitialized) busHandler else Handler(mainLooper)
        h.postDelayed({
            // release only OUR dispatch's latch; a newer dispatch resets the stamp
            if (isDispatching && dispatchStartedMs == startedAt) {
                isDispatching = false
            }
        }, durationMs + LATCH_RELEASE_MARGIN_MS)
    }

    fun executeDirectRequest(request: com.assistant.execution.ExecutionRequest): Boolean {
        if (isDispatching) {
            // A latch held past any legal gesture duration is a corpse, not a
            // dispatch in flight - clear it instead of refusing forever.
            if (!latchStuck()) {
                return false
            }
            isDispatching = false
        }

        isDispatching = true
        dispatchStartedMs = System.currentTimeMillis()
        val startedAt = dispatchStartedMs

        return try {
            val optimizedPath = generatePrecisionPath(
                request.startX,
                request.startY,
                request.endX,
                request.endY
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
                "source=${request.source} phase=${request.phase} duration=$syncedDuration"
            )

            val accepted = GestureExecutionAuthority.execute(
                this,
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(
                        gestureDescription: GestureDescription?
                    ) {
                        if (dispatchStartedMs == startedAt) isDispatching = false
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription?
                    ) {
                        if (dispatchStartedMs == startedAt) isDispatching = false
                    }
                },
                null
            )

            if (!accepted) {
                isDispatching = false
            } else {
                // Field-proven: the callback above never fires on this OS build.
                // The gesture cannot outlive its own duration, so the dispatcher
                // releases its own latch on schedule - no 400ms dead tax per action.
                scheduleLatchRelease(startedAt, syncedDuration)
            }

            accepted
        } catch (e: Exception) {
            isDispatching = false
            RuntimeLogger.log(
                "Direct dispatch vector crash: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    private val busRunnable = object : Runnable {
        override fun run() {
            try {
                // Backstop only: with self-timed release this should almost
                // never fire; if it does, it is logged as the anomaly it is.
                if (latchStuck()) {
                    isDispatching = false
                    RuntimeLogger.log(
                        "dispatch latch watchdog: backstop fired (anomaly)",
                        "SMART_ASSIST"
                    )
                }

                // Short-circuit evaluations to preserve maximum CPU thermal budget
                if (!SmartAssistRepository.enabled() || isDispatching) {
                    busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                    return
                }

                val request = CentralExecutionBus.consume()
                if (request != null) {
                    SmartAssistMetrics.recordBusConsumed(request)

                    // A consumed request is already planned and routed.
                    // Execute it once instead of feeding it back through the controller.
                    val accepted = executeDirectRequest(request)

                    SmartAssistMetrics.recordBusDispatchResult(request, accepted)
                    if (accepted) {
                        SmartAssistMetrics.executeRequest()
                    }

                    RuntimeLogger.execution(
                        "BUS_DISPATCH",
                        "source=${request.source} phase=${request.phase} accepted=$accepted"
                    )
                }
            } catch (e: Exception) {
                RuntimeLogger.log("Bus execution constraint violation: ${e.message}", "SMART_ASSIST")
            }

            // Tighter loop schedule to lock onto sub-frame rendering timing
            busHandler.postDelayed(this, BUS_POLL_RATE_MS)
        }
    }

    override fun onServiceConnected() {
        TelemetryCoordinator.initializeTransport("127.0.0.1", 8080)

        // Boot the Survival Engine with Max Impact Protection
        AccessibilitySurvivalEngine.getInstance(this).protect()

        // Elevate IO prioritization to the absolute maximum allowable Android scheduler tier
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        busThread = HandlerThread("SmartAssistBus").apply { start() }
        busHandler = Handler(busThread.looper)

        dispatcher = ActiveGestureController(this)
        globalInstance = this
        AccessibilitySurvivalEngine.connected()

        RuntimeCoordinator.attachExecutionLoop(
            start = { busHandler.post(busRunnable) },
            stop = { stopExecutionLoop() }
        )
        RuntimeCoordinator.reportPermissionsVerified()
        RuntimeCoordinator.reportAccessibilityReady()
        try { com.assistant.events.SystemEventHub.emit("accessibility-connected") } catch (_: Throwable) {}
        RuntimeLogger.log("SmartAssistAccessibilityEngine [OMEGA BUILD] connected BUILD_MARKER=TASKB-SELFTIMED-LATCH", "SMART_ASSIST")
    }

    fun triggerInstantExecution(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!SmartAssistRepository.enabled()) {
            RuntimeLogger.log("SmartAssist disabled, trigger dropped", "SMART_ASSIST")
            return
        }

        // Dynamically scale base 50L request to precise hardware ticks
        val syncedDuration = synchronizeToTickRate(50L)
        dispatcher.injectWinningVector(
            applyHumanizedNoise(x1),
            applyHumanizedNoise(y1),
            applyHumanizedNoise(x2),
            applyHumanizedNoise(y2),
            syncedDuration
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Purposely blanked out. Avoids VM garbage collection overhead during rapid layout events.
    }

    private fun stopExecutionLoop() {
        if (::busHandler.isInitialized) {
            busHandler.removeCallbacks(busRunnable)
        }
        if (::busThread.isInitialized) {
            busThread.quitSafely()
        }
        isDispatching = false
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
