package com.assistant.overlay.interceptor

object ReboundSaveActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        // REBOUND SAVE: Explosive micro-swipe from center/low position to instantly block second attempts.
        // Does not dive fully to the ground; stays on feet/legs for immediate secondary reaction.
        // No64-D14: parity with RushOutActionEngine — clamp degenerate inputs.
        val safeWidth = width.coerceAtLeast(0f)
        val safeHeight = height.coerceAtLeast(0f)
        return floatArrayOf(
            safeWidth * 0.50f, safeHeight * 0.75f,
            safeWidth * 0.50f, safeHeight * 0.60f
        )
    }
}