package com.assistant.overlay.interceptor

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.interceptor.SmartAssistPipeline

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.abs

class ActiveGestureController(
    private val service: AccessibilityService
) {

    fun injectWinningVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ) {

        val deltaX = abs(endX - startX)
        val deltaY = abs(endY - startY)

        val mode =
            when {
                deltaX > 500f -> 1
                deltaY > 500f -> 2
                else -> 0
            }

        SmartAssistPipeline.computeOptimalVector(
            startX,
            startY,
            endX,
            endY,
            mode
        )

        SmartAssistPipeline.isPanicStateActive =
            duration <= 50L

        val path = Path()

        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0,
                duration
            )

        val builder =
            GestureDescription.Builder()

        builder.addStroke(stroke)

        RuntimeLogger.log(
            "Trajectory produced mode=$mode panic=${SmartAssistPipeline.isPanicStateActive}",
            "SMART_ASSIST"
        )

        service.dispatchGesture(
            builder.build(),
            null,
            null
        )
    }
}
