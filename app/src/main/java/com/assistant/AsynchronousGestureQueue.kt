package com.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
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
        queue.offer(request)
        processNext()
    }

    private fun processNext() {
        if (!isProcessing.compareAndSet(false, true)) return
        if (accessibilityService == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
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
            path.moveTo(smoothedPoints[0], smoothedPoints[1])
            for (i in 1 until 11) {
                val idx = i * 3
                if (idx + 1 < smoothedPoints.size) {
                    path.lineTo(smoothedPoints[idx], smoothedPoints[idx + 1])
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
            Log.e(TAG, "Gesture dispatch failed", e)
            isProcessing.set(false)
            processNext()
        }
    }
}
