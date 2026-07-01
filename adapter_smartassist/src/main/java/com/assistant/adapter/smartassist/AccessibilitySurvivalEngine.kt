package com.assistant.adapter.smartassist

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilitySurvivalEngine(
    private val context: Context? = null
) {

    companion object {
        private val connectedState = AtomicBoolean(false)

        @JvmStatic
        fun connected() {
            connectedState.set(true)
        }

        @JvmStatic
        fun interrupted() {
            connectedState.set(false)
        }

        @JvmStatic
        fun missing() {
            connectedState.set(false)
        }

        @JvmStatic
        fun active(): Boolean {
            return connectedState.get()
        }
    }

    fun isReady(): Boolean {
        return active()
    }

    fun protect() {
        connected()
    }

    fun release() {
        interrupted()
    }
}
