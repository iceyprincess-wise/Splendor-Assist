package com.assistant.render

import android.view.Choreographer
import com.assistant.diagnostic.RuntimeLogger

object ChoreographerRenderLoop : Choreographer.FrameCallback {
    private var choreographer: Choreographer? = null
    private val pendingUpdates = mutableListOf<() -> Unit>()
    private var isRunning = false
    
    fun start() {
        if (isRunning) return
        choreographer = Choreographer.getInstance()
        isRunning = true
        choreographer?.postFrameCallback(this)
    }
    
    fun stop() {
        isRunning = false
        choreographer?.removeFrameCallback(this)
        choreographer = null
        synchronized(pendingUpdates) { pendingUpdates.clear() }
    }
    
    fun postUpdate(update: () -> Unit) {
        synchronized(pendingUpdates) { pendingUpdates.add(update) }
    }
    
    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        val updatesToRun = synchronized(pendingUpdates) {
            val copy = pendingUpdates.toList()
            pendingUpdates.clear()
            copy
        }
        for (update in updatesToRun) {
            try { update() } catch (e: Throwable) {
                RuntimeLogger.log("ChoreographerRenderLoop dropped failing overlay update: ${e.message}", "ChoreographerRenderLoop")
            }
        }
        choreographer?.postFrameCallback(this)
    }
}
