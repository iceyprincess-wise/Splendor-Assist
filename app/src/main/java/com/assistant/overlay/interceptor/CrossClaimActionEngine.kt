package com.assistant.overlay.interceptor
import com.assistant.NativePorts


object CrossClaimActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        // No65-D24: parity with RushOutActionEngine (no64-D14) — clamp
        // degenerate inputs before the NativePorts.vector port call
        // (pure Kotlin, not JNI — see NativePorts.kt).
        val out = FloatArray(4)
        NativePorts.vector(width.coerceAtLeast(0f), height.coerceAtLeast(0f), out)
        return out
    }
}
