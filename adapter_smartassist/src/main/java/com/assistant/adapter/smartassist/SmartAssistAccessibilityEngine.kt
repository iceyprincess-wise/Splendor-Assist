package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus


import com.assistant.adapter.smartassist.AccessibilitySurvivalEngine

class SmartAssistAccessibilityEngine : AccessibilityService() {

    companion object {
        @Volatile
        var globalInstance: SmartAssistAccessibilityEngine? = null
    }

    private lateinit var dispatcher: ActiveGestureController
    private lateinit var busHandler: Handler
    private lateinit var busThread: HandlerThread

    private val busRunnable = object : Runnable {
        override fun run() {
            try {
                if (!SmartAssistRepository.enabled()) {
                    busHandler.postDelayed(this, 100L)
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

                    val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                            busHandler.postDelayed({}, 10L)
                        }
                    }, null)
                    if (dispatched) {
                        SmartAssistMetrics.executeRequest()
                        RuntimeLogger.log("Gesture executed phase=${request.phase}", "SMART_ASSIST")
                    } else {
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
