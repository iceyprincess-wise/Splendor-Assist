package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlin.random.Random

object SmartAssistUltimateCorrector {

    private const val TAG = "UltimateCorrector"

    /**
     * Executes a flawless Purple (Stunning) Shot while simultaneously preserving 
     * space in the OS queue for active background tracking engines.
     */
    fun executePerfectPurpleShot(
        service: AccessibilityService,
        dashButtonX: Float,
        dashButtonY: Float,
        shootButtonX: Float,
        shootButtonY: Float,
        targetAngleRad: Double,
        activeEnginePath: Path? = null // Allows ongoing engine tracks to ride along for free
    ): Boolean {
        try {
            val builder = GestureDescription.Builder()

            // 1. Channel A: The Dash/Sprint Modifier Stroke
            val dashPath = Path().apply { moveTo(dashButtonX, dashButtonY) }
            val dashStroke = GestureDescription.StrokeDescription(dashPath, 0L, 20L)
            builder.addStroke(dashStroke)

            // 2. Channel B: The Directional Shoot Swipe
            val shootPath = Path().apply {
                moveTo(shootButtonX, shootButtonY)
                val sweepRadius = 45.0f // Perfect distance for stunning modifier recognition
                val endX = shootButtonX + (Math.cos(targetAngleRad) * sweepRadius).toFloat()
                val endY = shootButtonY + (Math.sin(targetAngleRad) * sweepRadius).toFloat()
                lineTo(endX, endY)
            }
            
            // Randomize duration tightly (+/- 1ms) to keep it humanized without losing engine timing
            val humanizedDuration = 40L + Random.nextLong(-1, 2)
            val shootStroke = GestureDescription.StrokeDescription(shootPath, 0L, humanizedDuration)
            builder.addStroke(shootStroke)

            // 3. Channel C: Parallel Engine Injection (Zero Compromise Layer)
            // If MagneticFeet or DashAnchor has a path ready, it hitches a ride in the same packet
            if (activeEnginePath != null) {
                val engineStroke = GestureDescription.StrokeDescription(activeEnginePath, 0L, 12L)
                builder.addStroke(engineStroke)
            }

            // Dispatch everything in one single, high-priority hardware token
            service.dispatchGesture(builder.build(), null, null)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Ultimate Corrector pipeline drop: ${e.message}")
            return false
        }
    }

    /**
     * Dedicated Button Responsiveness Booster
     * Forces Android to flush the input buffer immediately when navigating menus or passing
     */
    fun forceButtonResponse(service: AccessibilityService, targetX: Float, targetY: Float) {
        val clickPath = Path().apply { moveTo(targetX, targetY) }
        // A ultra-short 8ms stroke clears stuck touch tracks on the screen layout
        val stroke = GestureDescription.StrokeDescription(clickPath, 0L, 8L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }
}
