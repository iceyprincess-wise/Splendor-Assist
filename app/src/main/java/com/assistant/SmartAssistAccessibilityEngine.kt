package com.assistant

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
import com.assistant.vision.ForegroundGate
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
        @Volatile
        private var dispatchingSource: com.assistant.execution.ExecutionSource? = null
        private const val DISPATCH_LATCH_TIMEOUT_MS = 250L
        private const val LATCH_RELEASE_MARGIN_MS = 0L  

        private fun latchStuck(): Boolean =
            isDispatching &&
                System.currentTimeMillis() - dispatchStartedMs > DISPATCH_LATCH_TIMEOUT_MS

        @Volatile
        private var serverTickRateMs = 16.6667f 
        
        private const val MAX_SAFE_DURATION_MS = 250L     
        private const val NOISE_AMPLITUDE_PX = 1.0f
        private const val BUS_POLL_RATE_MS = 2L
        private const val DISPATCH_RATE_WINDOW_MS = 10_000L

        // Screen dimensions for bounds safety validation
        @Volatile
        var displayWidth: Float = 2400f
        @Volatile
        var displayHeight: Float = 1080f
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread

    private val windowDispatches = AtomicLong(0L)
    @Volatile private var windowStartMs = 0L

    private fun synchronizeToTickRate(targetDuration: Long): Long {
        if (targetDuration <= 0L) return serverTickRateMs.roundToLong()
        return max(8L, min(targetDuration, MAX_SAFE_DURATION_MS))
    }

    private fun generatePrecisionPath(startX: Float, startY: Float, endX: Float, endY: Float): Path {
        val path = Path()
        val safeStartX = startX.coerceIn(0f, displayWidth)
        val safeStartY = startY.coerceIn(0f, displayHeight)
        path.moveTo(safeStartX, safeStartY)

        if (startX == endX && startY == endY) {
            path.lineTo(safeStartX, safeStartY)
        } else {
            val safeEndX = endX.coerceIn(0f, displayWidth)
            val safeEndY = endY.coerceIn(0f, displayHeight)
            path.lineTo(safeEndX, safeEndY)
        }
        return path
    }

    private fun scheduleLatchRelease(startedAt: Long, durationMs: Long) {
        val h = if (::busHandler.isInitialized) busHandler else Handler(mainLooper)
        h.postDelayed({
            if (isDispatching && dispatchStartedMs == startedAt) {
                isDispatching = false
                dispatchingSource = null
            }
        }, durationMs + LATCH_RELEASE_MARGIN_MS)
    }

    private fun recordDispatchForRate() {
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = now
        windowDispatches.incrementAndGet()
        val elapsed = now - windowStartMs
        if (elapsed >= DISPATCH_RATE_WINDOW_MS) {
            windowDispatches.set(0L)
            windowStartMs = now
        }
    }

    fun executeDirectRequest(request: com.assistant.execution.ExecutionRequest): Boolean {
        // Task 8 Safety Gate: Prevent ghost-touches if screen bounds are invalid or coordinates are non-positive
        if (request.startX <= 0f && request.startY <= 0f && request.endX <= 0f && request.endY <= 0f) {
            return false
        }
        if (!ForegroundGate.isGameForeground()) {
            return false
        }

        val isMove = request.phase == com.assistant.runtime.ActionClass.MOVE.ordinal
        
        if (isDispatching) {
            if (!isMove && !latchStuck()) {
                return false
            }
            isDispatching = false
        }

        isDispatching = true
        dispatchStartedMs = System.currentTimeMillis()
        dispatchingPriority = HybridExecutionTerminal.priority(request.source)
        dispatchingSource = request.source
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

            val accepted = GestureExecutionAuthority.execute(
                this,
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (dispatchStartedMs == startedAt) {
                            isDispatching = false
                            dispatchingSource = null
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (dispatchStartedMs == startedAt) {
                            isDispatching = false
                            dispatchingSource = null
                        }
                    }
                },
                null,
                origin = "bus:${request.source}",
                actionIdParam = request.actionId,
                busAcceptedParam = request.busAccepted,
                busConsumedParam = request.busConsumed
            )

            if (!accepted) {
                isDispatching = false
                dispatchingSource = null
            } else {
                scheduleLatchRelease(startedAt, syncedDuration)
                recordDispatchForRate()
            }

            accepted
        } catch (e: Exception) {
            isDispatching = false
            dispatchingSource = null
            false
        }
    }

    private val busRunnable = object : Runnable {
        override fun run() {
            try {
                if (latchStuck()) {
                    isDispatching = false
                    dispatchingSource = null
                }

                if (!SmartAssistRepository.enabled() || !ForegroundGate.isGameForeground()) {
                    busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                    return
                }

                if (isDispatching) {
                    val headSource = CentralExecutionBus.peekSource()
                    val headPriority = if (headSource != null) HybridExecutionTerminal.priority(headSource) else Int.MIN_VALUE
                    val preempt =
                        headSource != null &&
                            (headPriority > dispatchingPriority || 
                             (headSource == com.assistant.execution.ExecutionSource.GOALKEEPER && dispatchingSource == com.assistant.execution.ExecutionSource.GOALKEEPER))
                    if (!preempt) {
                        busHandler.postDelayed(this, BUS_POLL_RATE_MS)
                        return
                    }
                    isDispatching = false
                    dispatchingSource = null
                }

                val request = CentralExecutionBus.consume()
                if (request != null) {
                    SmartAssistMetrics.recordBusConsumed(request)

                    val accepted = executeDirectRequest(request)

                    SmartAssistMetrics.recordBusDispatchResult(request, accepted)
                    if (accepted) {
                        SmartAssistMetrics.executeRequest()
                    }
                }
            } catch (_: Exception) {
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
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                displayWidth = metrics.widthPixels.toFloat()
                displayHeight = metrics.heightPixels.toFloat()
            }
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
        AsynchronousGestureQueue.bindService(this)
        AccessibilitySurvivalEngine.connected()

        RuntimeCoordinator.attachExecutionLoop(
            start = { busHandler.post(busRunnable) },
            stop = { stopExecutionLoop() }
        )
        RuntimeCoordinator.reportPermissionsVerified()
        RuntimeCoordinator.reportAccessibilityReady()
        try { com.assistant.events.SystemEventHub.emit("accessibility-connected") } catch (_: Throwable) {}
    }

    fun triggerInstantExecution(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!SmartAssistRepository.enabled() || !ForegroundGate.isGameForeground()) {
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
