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
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.adapter.smartassist.AccessibilitySurvivalEngine
import java.util.concurrent.ThreadLocalRandom
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
        private const val DISPATCH_LATCH_TIMEOUT_MS = 250L
        private const val LATCH_RELEASE_MARGIN_MS = 0L  

        private fun latchStuck(): Boolean =
            isDispatching &&
                System.currentTimeMillis() - dispatchStartedMs > DISPATCH_LATCH_TIMEOUT_MS

        @Volatile
        private var serverTickRateMs = 16.6667f 
        
        // UNRESTRICTED LETHAL CAP: Raised to 250ms to allow long, authoritative control holds.
        private const val MAX_SAFE_DURATION_MS = 250L     
        private const val NOISE_AMPLITUDE_PX = 1.0f     // Dropped from 3.85f to prevent trajectory deviations
        private const val BUS_POLL_RATE_MS = 2L  // Accelerated polling down to 2ms for tighter sub-frame synchronization
        private const val DISPATCH_RATE_WINDOW_MS = 10_000L
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread

    private val windowDispatches = AtomicLong(0L)
    @Volatile private var windowStartMs = 0L

    private fun applyHumanizedNoise(value: Float): Float {
        val noise = (ThreadLocalRandom.current().nextFloat() * 2 - 1) * NOISE_AMPLITUDE_PX
        return value + noise
    }

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

    private fun scheduleLatchRelease(startedAt: Long, durationMs: Long) {
        val h = if (::busHandler.isInitialized) busHandler else Handler(mainLooper)
        h.postDelayed({
            if (isDispatching && dispatchStartedMs == startedAt) {
                isDispatching = false
            }
        }, durationMs + LATCH_RELEASE_MARGIN_MS)
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

    fun executeDirectRequest(request: com.assistant.execution.ExecutionRequest): Boolean {
        if (isDispatching) {
            if (!latchStuck()) {
                return false
            }
            isDispatching = false
        }

        isDispatching = true
        dispatchStartedMs = System.currentTimeMillis()
        dispatchingPriority = HybridExecutionTerminal.priority(request.source)
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
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (dispatchStartedMs == startedAt) isDispatching = false
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (dispatchStartedMs == startedAt) isDispatching = false
                    }
                },
                null,
                origin = "bus:${request.source}"
            )

            if (!accepted) {
                isDispatching = false
            } else {
                scheduleLatchRelease(startedAt, syncedDuration)
                recordDispatchForRate()
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
                if (latchStuck()) {
                    isDispatching = false
                    RuntimeLogger.log(
                        "dispatch latch watchdog: backstop fired (anomaly)",
                        "SMART_ASSIST"
                    )
                }

                if (!SmartAssistRepository.enabled()) {
                    busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                    return
                }

                if (isDispatching) {
                    val headSource = CentralExecutionBus.peekSource()
                    val preempt =
                        headSource != null &&
                            HybridExecutionTerminal.priority(headSource) > dispatchingPriority
                    if (!preempt) {
                        busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                        return
                    }
                    isDispatching = false
                    RuntimeLogger.execution(
                        "BUS_PREEMPT",
                        "head=$headSource overIntPriority=$dispatchingPriority"
                    )
                }

                val request = CentralExecutionBus.consume()
                if (request != null) {
                    SmartAssistMetrics.recordBusConsumed(request)

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

            busHandler.postDelayed(this, BUS_POLL_RATE_MS)
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

        TelemetryCoordinator.initializeTransport("127.0.0.1", 8080)
        AccessibilitySurvivalEngine.getInstance(this).protect()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        busThread = HandlerThread("SmartAssistBus", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
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
        RuntimeLogger.log("SmartAssistAccessibilityEngine [OMEGA BUILD] connected BUILD_MARKER=TASKC-PREEMPTIVE-DISPATCH", "SMART_ASSIST")
    }

    fun triggerInstantExecution(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!SmartAssistRepository.enabled()) {
            RuntimeLogger.log("SmartAssist disabled, trigger dropped", "SMART_ASSIST")
            return
        }

        val syncedDuration = synchronizeToTickRate(50L)
        dispatcher.injectWinningVector(
            (x1),
            (y1),
            (x2),
            (y2),
            syncedDuration
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

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
