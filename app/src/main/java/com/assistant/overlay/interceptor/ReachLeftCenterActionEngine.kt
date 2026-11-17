package com.assistant.overlay.interceptor

object ReachLeftCenterActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        // No64-D14: parity with RushOutActionEngine — clamp degenerate inputs.
        val safeWidth = width.coerceAtLeast(0f)
        val safeHeight = height.coerceAtLeast(0f)
        return floatArrayOf(
            safeWidth * 0.50f, safeHeight * 0.72f,
            safeWidth * 0.10f, safeHeight * 0.55f
        )
    }
}