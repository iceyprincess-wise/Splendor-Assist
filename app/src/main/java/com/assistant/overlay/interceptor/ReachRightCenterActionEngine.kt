package com.assistant.overlay.interceptor

object ReachRightCenterActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        return floatArrayOf(
            width * 0.50f, height * 0.72f,
            width * 0.90f, height * 0.55f
        )
    }
}