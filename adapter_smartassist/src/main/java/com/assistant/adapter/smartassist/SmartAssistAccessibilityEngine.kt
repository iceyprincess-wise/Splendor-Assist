package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.HybridExecutionTerminal


import com.assistant.adapter.smartassist.AccessibilitySurvivalEngine

class SmartAssistAccessibilityEngine : AccessibilityService() {

    fun executeDirectRequest(request: com.assistant.execution.ExecutionRequest): Boolean {
        return try {
            val path = android.graphics.Path().apply {
                moveTo(request.startX, request.startY)
                lineTo(request.endX, request.endY)
            }

            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path,
                0,
                request.duration.coerceAtMost(85L)
            )

            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            RuntimeLogger.execution("DIRECT_DISPATCH","phase=${request.phase}")

            dispatchGesture(gesture,null,null)
        } catch(e: Exception) {
            false
        }
    }


    companion object {
        @Volatile
        var globalInstance: SmartAssistAccessibilityEngine? = null
        @Volatile
        var isDispatching = false
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread

    private val busRunnable = object : Runnable {
        override fun run() {
            try {
                if (!SmartAssistRepository.enabled() || isDispatching) {
                    busHandler.postDelayed(this, 10L)
                    return
                }

                val request = CentralExecutionBus.consume()
                if (request != null) {
                    val path = Path().apply {
                        moveTo(request.startX, request.startY)
                        lineTo(request.endX, request.endY)
                    }

                    val stroke = GestureDescription.StrokeDescription(
                        path,
                        0,
                        request.duration.coerceAtMost(85L)
                    )

                    val builder = GestureDescription.Builder().apply {
                        addStroke(stroke)
                    }

                    isDispatching = true
                    val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                            isDispatching = false
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            super.onCancelled(gestureDescription)
                            isDispatching = false
                        }
                    }, null)
                    if (!dispatched) isDispatching = false
                    if (dispatched) {
                        RuntimeLogger.execution("GESTURE_SUCCESS","phase=${request.phase}")
                        SmartAssistMetrics.executeRequest()
                        RuntimeLogger.log("Gesture executed phase=${request.phase}", "SMART_ASSIST")
                    } else {
                        RuntimeLogger.execution("GESTURE_FAILED","phase=${request.phase}")
                        RuntimeLogger.log("Gesture dispatch failed", "SMART_ASSIST")
                    }
                }
            } catch (e: Exception) {
                RuntimeLogger.log("Bus execution error: ${e.message}", "SMART_ASSIST")
            }

            busHandler.postDelayed(this, 10L)
        }
    }

    override fun onServiceConnected() {
        TelemetryCoordinator.initializeTransport("127.0.0.1",8080)
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        busThread = HandlerThread("SmartAssistBus").apply { start() }
        busHandler = Handler(busThread.looper)

        dispatcher = ActiveGestureController(this)
        globalInstance = this
        AccessibilitySurvivalEngine.connected()

        busHandler.post(busRunnable)
        RuntimeLogger.log("SmartAssistAccessibilityEngine connected", "SMART_ASSIST")
    }

    fun triggerInstantExecution(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!SmartAssistRepository.enabled()) {
            RuntimeLogger.log("SmartAssist disabled, trigger ignored", "SMART_ASSIST")
            return
        }
        dispatcher.injectWinningVector(x1, y1, x2, y2, 50L)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {
        busHandler.removeCallbacks(busRunnable)
        busThread.quitSafely()
        AccessibilitySurvivalEngine.interrupted()
        RuntimeLogger.log("SmartAssistAccessibilityEngine interrupted", "SMART_ASSIST")
    }
}
