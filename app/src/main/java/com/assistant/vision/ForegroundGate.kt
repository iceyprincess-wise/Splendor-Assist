package com.assistant.vision

// F49_PATCH_V1.vision

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
    @Synchronized
    fun install(app: Application?) {
        if (installed || app == null) return
        // No62-D9: installed used to be set BEFORE
        // registerActivityLifecycleCallbacks, so a throw there left the
        // gate permanently uninstalled (installed blocked every retry)
        // and push() never ran. Register first; only a successful
        // registration marks the gate installed.
        // No62-D10: install used to be unsynchronised, so two threads
        // could both pass the installed check and register the callbacks
        // twice. Lifecycle callbacks run on the main thread, so the
        // double count would stay balanced — but the installed-flag race
        // with D9 is real, and one annotation closes it.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                lastActivity = a.javaClass.simpleName
                push()
            }
            override fun onActivityStopped(a: Activity) {
                if (started > 0) started--
                // No62-D12: the old code left lastActivity naming the
                // activity that just stopped, so stats() reported a
                // "last" activity although no UI was visible at all.
                if (started <= 0) lastActivity = "none"
                push()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        installed = true
        push()
        log("ForegroundGate installed")
    }

    private fun push() {
        try {
            com.assistant.VisionTrust.setGameForeground(started <= 0)
        } catch (t: Throwable) {
            // No62-D11: the old code swallowed this silently, so a
            // VisionTrust failure left the gate and VisionTrust diverged
            // with no trace. The gate state (started) is still the
            // truth at the read site — VisionTrust.isGameForeground()
            // reads ownUiVisible() directly — but the push failure
            // itself must be visible in the log.
            log("ForegroundGate push failed: " + t.message)
        }
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
