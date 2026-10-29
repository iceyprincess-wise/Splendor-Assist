package com.assistant.overlay.interceptor


object CrossClaimActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        val out = FloatArray(4)
        NativePorts.vector(width, height, out)
        return out
    }
}
