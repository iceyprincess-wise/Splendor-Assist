package com.assistant

import android.app.ActivityManager

import android.content.ComponentCallbacks2
import android.content.Context

import android.content.res.Configuration

import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Phase M (M-8): the memory-state listener interface is deleted — it
// had zero implementers tree-wide and the only construction passed no
// listener, so the three notification call sites were no-ops in effect.
class MemoryStabilityOptimizer(
    private val context: Context
) : ComponentCallbacks2 {

    private val isInitialized = AtomicBoolean(false)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    // No68-E1-D22: the scope was a val cancelled for good by terminate(),
    // so initialise() after a terminate() launched the monitor into a
    // dead scope and the optimiser silently never ran again. Recreate it.
    private var coroutineScope = newScope()
    private fun newScope() = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null

    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            // No68-E1-D22b: a scope cancelled by an earlier terminate()
            // cannot launch again — rebuild it here, before monitoring.
            if (!coroutineScope.isActive) coroutineScope = newScope()
            context.registerComponentCallbacks(this)
            startActiveMonitoring()
        }
    }

    /** L8 (batch 1): the liveness reporter's read — true once initialize() has run and terminate() has not. */
    fun isInitialized(): Boolean = isInitialized.get()

    fun terminate() {
        if (isInitialized.compareAndSet(true, false)) {
            context.unregisterComponentCallbacks(this)
            monitorJob?.cancel()
            monitorJob = null
            coroutineScope.cancel()
        }
    }

    private fun startActiveMonitoring() {
        monitorJob = coroutineScope.launch {
            while (isActive) {
                checkMemoryState()
                // Adaptive poll rate: High frequency to catch spikes before the OS forces a trim
                delay(3000L) 
            }
        }
    }

    private fun checkMemoryState() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // Calculate a critical threshold (15% safety buffer above the OS fatal kill threshold)
        val criticalThreshold = memoryInfo.threshold + (memoryInfo.threshold * 0.15)

        if (memoryInfo.availMem <= criticalThreshold || memoryInfo.lowMemory) {
            executeAggressiveCleanup()
        }
    }

    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                executeAggressiveCleanup()
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // No68-E1-D24: the old code fired a full System.gc() on
                // every MILD trim — including UI_HIDDEN and BACKGROUND,
                // which arrive while the game is foregrounded and the
                // device is healthy. System.gc() is a global stop-the-
                // world pause on the gameplay path, and this class holds
                // no caches of its own to free, so the GC bought nothing
                // but a frame stall. Mild trims now do nothing here; the
                // OS reclaims what it needs without a forced collection.
            }
        }
    }

    override fun onLowMemory() {
        executeAggressiveCleanup()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No memory action required for configuration changes
    }

    /**
     * Executes Garbage Collection on a dedicated I/O thread.
     * EXTREMELY IMPORTANT: Never call Runtime.getRuntime().gc() on the main thread
     * in a high-frequency injection environment. It blocks the UI thread, causing
     * coordinate translation stutter and missing server-tick boundaries.
     */
    private fun executeAggressiveCleanup() {
        coroutineScope.launch(Dispatchers.IO) {
            System.gc()
            System.runFinalization()
            // Suggest the OS to yield thread execution to allow the GC to finalize
            Thread.yield()
        }
    }
}
