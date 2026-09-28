package com.assistant.overlay.interceptor

import com.assistant.NativeBridge

object CrossClaimActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        val out = FloatArray(4)
        NativeBridge.nativeCrossClaimVector(width, height, out)
        return out
    }
}
