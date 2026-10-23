package com.assistant

import java.util.concurrent.atomic.AtomicBoolean

object UserTouchState {
    private val touching = AtomicBoolean(false)

    fun setTouching(value: Boolean) {
        touching.set(value)
    }

    fun isTouching(): Boolean = touching.get()
}
