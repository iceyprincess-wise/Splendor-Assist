package com.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.assistant.vision.ForegroundGate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object AsynchronousGestureQueue {
    private const val TAG = "GestureQueue"
    private val queue = ConcurrentLinkedQueue<GestureRequest>()
    private val isProcessing = AtomicBoolean(false)
    
    private var accessibilityService: AccessibilityService? = null

    data class GestureRequest(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long,
        val priority: Int = 0
    )

    fun bindService(service: AccessibilityService) {
        accessibilityService = service
    }

    fun enqueue(request: GestureRequest) {
        // Task 8 Safety Gate: Validate coordinates and game foreground before enqueuing
        if (request.startX <= 0f && request.startY <= 0f && request.endX <= 0f && request.endY <= 0f) return
        if (!ForegroundGate.isGameForeground()) return

        queue.offer(request)
        processNext()
    }

    private fun processNext() {
        if (!isProcessing.compareAndSet(false, true)) return
        if (accessibilityService == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !ForegroundGate.isGameForeground()) {
            isProcessing.set(false)
            return
        }

        val req = queue.poll()
        if (req == null) {
            isProcessing.set(false)
            return
        }

        try {
            val smoothedPoints = FloatArray(33)
            NativeBridge.nativeSmoothVector(
                req.startX, req.startY, req.endX, req.endY,
                req.durationMs.toFloat(), smoothedPoints
            )

            val path = Path()
            path.moveTo(smoothedPoints[0].coerceAtLeast(0f), smoothedPoints[1].coerceAtLeast(0f))
            for (i in 1 until 11) {
                val idx = i * 3
                if (idx + 1 < smoothedPoints.size) {
                    path.lineTo(smoothedPoints[idx].coerceAtLeast(0f), smoothedPoints[idx + 1].coerceAtLeast(0f))
                }
            }

            val stroke = GestureDescription.StrokeDescription(
                path, 0, req.durationMs
            )
            
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isProcessing.set(false)
                    processNext()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isProcessing.set(false)
                    processNext()
                }
            }
            GestureExecutionAuthority.execute(
                accessibilityService!!, gesture, callback, null, origin = "AsynchronousGestureQueue"
            )
        } catch (e: Exception) {
            isProcessing.set(false)
            processNext()
        }
    }
}
