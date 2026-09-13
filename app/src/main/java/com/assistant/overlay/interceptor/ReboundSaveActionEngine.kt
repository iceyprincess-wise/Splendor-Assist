package com.assistant.overlay.interceptor

object ReboundSaveActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        // REBOUND SAVE: Explosive micro-swipe from center/low position to instantly block second attempts.
        // Does not dive fully to the ground; stays on feet/legs for immediate secondary reaction.
        return floatArrayOf(
            width * 0.50f, height * 0.75f,
            width * 0.50f, height * 0.60f
        )
    }
}