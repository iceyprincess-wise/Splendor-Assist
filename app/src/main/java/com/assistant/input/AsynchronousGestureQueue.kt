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

        // MUTATION TOOL 3: High-frequency gesture splitting for zero-delay input
        val strobePackets = FloatArray(16)
        com.assistant.NativeBridge.nativeInjectStrobePackets(element.startX, element.startY, endX, endY, duration.toInt(), 4, strobePackets)
        
        val strobePath = Path().apply {
            moveTo(element.startX, element.startY)
            for (i in 0 until 4) {
                val idx = i * 4
                val px = strobePackets[idx + 1]
                val py = strobePackets[idx + 2]
                lineTo(px, py)
            }
            lineTo(endX, endY)
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(strobePath, 0L, duration.coerceAtLeast(2L)))
        val gesture = builder.build()
        GestureExecutionAuthority.execute(element.service, gesture, null, null, origin = "AsynchronousGestureQueue")
    }
}
