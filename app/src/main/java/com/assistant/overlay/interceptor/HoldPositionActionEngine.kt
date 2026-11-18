package com.assistant.overlay.interceptor

object HoldPositionActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        // No65-D24: parity with RushOutActionEngine (no64-D14) — clamp
        // degenerate inputs so a negative/zero size cannot produce
        // negative coordinates (X is never clamped anywhere downstream).
        val safeWidth = width.coerceAtLeast(0f)
        val safeHeight = height.coerceAtLeast(0f)

        return floatArrayOf(
            safeWidth * 0.50f,
            safeHeight * 0.72f,
            safeWidth * 0.50f,
            safeHeight * 0.72f
        )
    }
}
