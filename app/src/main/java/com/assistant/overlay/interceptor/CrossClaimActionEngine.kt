package com.assistant.overlay.interceptor
import com.assistant.NativePorts


object CrossClaimActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        val out = FloatArray(4)
        NativePorts.vector(width, height, out)
        return out
    }
}
