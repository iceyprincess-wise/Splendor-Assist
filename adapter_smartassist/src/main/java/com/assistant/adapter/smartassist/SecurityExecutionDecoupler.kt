package com.assistant.adapter.smartassist

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

class SecurityExecutionDecoupler {

    private val securityThread =
        HandlerThread(
            "SplendorAssist::ShieldCore",
            Process.THREAD_PRIORITY_BACKGROUND
        )

    private var securityHandler: Handler? = null

    fun initializeShield() {
        securityThread.start()
        securityHandler =
            Handler(
                securityThread.looper
            )
    }

    fun executeIsolatedSecurityCheck(
        crossCheckTask: Runnable
    ) {
        securityHandler?.post(
            crossCheckTask
        )
    }

    fun shutdownShield() {
        securityThread.quitSafely()
    }
}
