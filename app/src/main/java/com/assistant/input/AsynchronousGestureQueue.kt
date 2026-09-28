package com.assistant.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.assistant.GestureExecutionAuthority
import com.assistant.NativeBridge
import java.util.concurrent.ConcurrentLinkedQueue

object AsynchronousGestureQueue {
    private val gestureQueue = ConcurrentLinkedQueue<PackedGesture>()
    
    private data class PackedGesture(
        val startX: Float, val startY: Float,
        val packedData: Long,
        val service: AccessibilityService
    )

    fun enqueueGesture(service: AccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float, baseDuration: Long, w: Float, h: Float, isLagging: Boolean) {
        val packed = NativeBridge.nativeCompileMotionEvent(startX, startY, endX, endY, baseDuration, w, h, isLagging)
        gestureQueue.offer(PackedGesture(startX, startY, packed, service))
        processNextImmediate()
    }

    private fun processNextImmediate() {
        val element = gestureQueue.poll() ?: return
        val endX = ((element.packedData shr 32) and 0xFFFF).toFloat()
        val endY = ((element.packedData shr 16) and 0xFFFF).toFloat()
        val duration = (element.packedData and 0xFFFF)

        val path = Path().apply {
            moveTo(element.startX, element.startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(10L)))
            .build()
        GestureExecutionAuthority.execute(element.service, gesture, null, null)
    }
}
