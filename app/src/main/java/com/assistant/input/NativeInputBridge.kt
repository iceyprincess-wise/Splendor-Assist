package com.assistant.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.assistant.GestureExecutionAuthority

object NativeInputBridge {
    init {
        try { System.loadLibrary("splendor_native") } catch (_: Throwable) {}
    }

    external fun nativeInjectTap(x: Float, y: Float): Boolean
    external fun nativeInjectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean

    fun injectTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        // No66-D20: if the library failed to load, the init block above
        // swallowed it and this call would throw UnsatisfiedLinkError
        // instead of reaching the Accessibility fallback below.
        if (runCatching { nativeInjectTap(x, y) }.getOrDefault(false)) return true // Native success
        // Fallback to AccessibilityService (safe, but slower)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 2L)).build()
        return GestureExecutionAuthority.execute(service, gesture, null, null)
    }
    
    fun injectSwipe(service: AccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean {
        // No66-D20: same guard as injectTap — a missing library/symbol must
        // fall through to the Accessibility gesture, never throw.
        if (runCatching { nativeInjectSwipe(startX, startY, endX, endY, duration) }.getOrDefault(false)) return true
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        return GestureExecutionAuthority.execute(service, gesture, null, null)
    }
}
