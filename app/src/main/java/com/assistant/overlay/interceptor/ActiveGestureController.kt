package com.assistant.overlay.interceptor
import com.assistant.diagnostic.RuntimeLogger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class ActiveGestureController(private val service: AccessibilityService) {
    fun injectWinningVector(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        // Using sequential build method compatible with all API levels
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke) 
        RuntimeLogger.log("Gesture injection attempted via Vector", "GESTURE")
        
        service.dispatchGesture(builder.build(), null, null)
    }
}
