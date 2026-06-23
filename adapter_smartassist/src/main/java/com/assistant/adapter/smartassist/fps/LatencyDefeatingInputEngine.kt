package com.assistant.adapter.smartassist.fps

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class LatencyDefeatingInputEngine(
    private val service: AccessibilityService
) {

    private val preAllocatedPath = Path()

    fun injectZeroLatencySwipe(
        startX:Float,
        startY:Float,
        endX:Float,
        endY:Float,
        restrictedDuration:Long
    ){

        preAllocatedPath.reset()

        preAllocatedPath.moveTo(
            startX,
            startY
        )

        preAllocatedPath.lineTo(
            endX,
            endY
        )

        val stroke =
            GestureDescription.StrokeDescription(
                preAllocatedPath,
                0,
                restrictedDuration
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        service.dispatchGesture(
            gesture,
            object :
                AccessibilityService
                    .GestureResultCallback(){

                override fun onCompleted(
                    gestureDescription:GestureDescription?
                ){
                    preAllocatedPath.reset()
                }

                override fun onCancelled(
                    gestureDescription:GestureDescription?
                ){
                    preAllocatedPath.reset()
                }
            },
            null
        )
    }
}
