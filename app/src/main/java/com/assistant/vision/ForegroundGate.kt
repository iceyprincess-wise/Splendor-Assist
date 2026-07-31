package com.assistant.vision

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * GAP 1C — OWN-UI CAPTURE GATE
 *
 * MediaProjection captures the whole screen, including our own Control Room and
 * Diagnosis pages. Those screens are full of text and boxes, which the detector
 * reads as players (observed: players=100 on a 22-player pitch).
 *
 * Rule: while any of OUR activities is on screen, the frame is not game truth.
 */
object ForegroundGate {

    @Volatile private var started = 0
    @Volatile private var installed = false
    @Volatile private var skipped = 0L
    @Volatile private var lastActivity = "none"

    @JvmStatic
    fun install(app: Application?) {
        if (installed || app == null) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                lastActivity = a.javaClass.simpleName
                push()
            }
            override fun onActivityStopped(a: Activity) {
                if (started > 0) started--
                push()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        push()
        log("ForegroundGate installed")
    }

    private fun push() {
        try {
            com.assistant.adapter.smartassist.VisionTrust.setGameForeground(started == 0)
        } catch (_: Throwable) { }
    }

    /** true when this captured frame must be dropped before any processing */
    @JvmStatic
    fun shouldSkipCapture(): Boolean {
        if (started > 0) {
            skipped++
            if (skipped % 50L == 0L) log(stats())
            return true
        }
        return false
    }

    @JvmStatic
    fun ownUiVisible(): Boolean = started > 0

    @JvmStatic
    fun stats(): String =
        "ownUiVisible=" + (started > 0) + " last=" + lastActivity + " skippedFrames=" + skipped

    private fun log(m: String) {
        try { com.assistant.diagnostic.RuntimeLogger.log(m, "FGGATE") } catch (_: Throwable) { }
    }
}
