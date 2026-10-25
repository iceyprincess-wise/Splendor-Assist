package com.assistant

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UserTouchState {
    private val touching = AtomicBoolean(false)
    private val lastTouchTimeMs = AtomicLong(0L)
    // Dynamic window for active touch retention (ms)
    private const val TOUCH_RETENTION_WINDOW_MS = 2500L

    fun setTouching(value: Boolean) {
        touching.set(value)
        if (value) {
            lastTouchTimeMs.set(System.currentTimeMillis())
        }
    }

    fun registerTouchActivity() {
        touching.set(true)
        lastTouchTimeMs.set(System.currentTimeMillis())
    }

    fun isTouching(): Boolean {
        if (touching.get()) return true
        val elapsed = System.currentTimeMillis() - lastTouchTimeMs.get()
        return elapsed in 0..TOUCH_RETENTION_WINDOW_MS
    }
}
