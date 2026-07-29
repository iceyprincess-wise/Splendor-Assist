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
        val safeStartX = applyHumanizedNoise(startX)
        val safeStartY = applyHumanizedNoise(startY)
        path.moveTo(safeStartX, safeStartY)

        if (startX == endX && startY == endY) {
            // Zero-distance tap stabilization
            path.lineTo(safeStartX, safeStartY)
        } else {
            // High-frequency transit calculation
            path.lineTo(applyHumanizedNoise(endX), applyHumanizedNoise(endY))
        }
        return path
    }

    fun executeDirectRequest(request: com.assistant.execution.ExecutionRequest): Boolean {
        if (isDispatching) {
            return false
        }

        isDispatching = true

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
                        isDispatching = false
                        RuntimeLogger.execution(
                            "DIRECT_DISPATCH_COMPLETED",
                            "source=${request.source} phase=${request.phase}"
                        )
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription?
                    ) {
                        isDispatching = false
                        RuntimeLogger.execution(
                            "DIRECT_DISPATCH_CANCELLED",
                            "source=${request.source} phase=${request.phase}"
                        )
                    }
                },
                null
            )

            if (!accepted) {
                isDispatching = false
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
        RuntimeCoordinator.reportAccessibilityReady()
        RuntimeLogger.log("SmartAssistAccessibilityEngine [OMEGA BUILD] connected", "SMART_ASSIST")
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
