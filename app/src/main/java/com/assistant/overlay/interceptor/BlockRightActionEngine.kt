package com.assistant.overlay.interceptor

object BlockRightActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        return floatArrayOf(
            width * 0.50f,
            height * 0.72f,
            width * 0.70f,
            height * 0.55f
        )
    }
}
