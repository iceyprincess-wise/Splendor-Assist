package com.assistant.adapter.smartassist

import android.content.Context

class AccessibilitySurvivalEngine(
    private val context: Context? = null
) {
    companion object {
        @JvmStatic fun connected() {}
        @JvmStatic fun interrupted() {}
        @JvmStatic fun missing() {}
    }

    fun isReady(): Boolean = true
    fun protect() {}
    fun release() {}
}
