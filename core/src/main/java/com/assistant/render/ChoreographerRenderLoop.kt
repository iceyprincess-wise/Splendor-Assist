package com.assistant.render

import android.view.Choreographer
import com.assistant.diagnostic.RuntimeLogger

object ChoreographerRenderLoop : Choreographer.FrameCallback {
    // CRL-D3: lifecycle flags are @Volatile — postUpdate is provably called
    // from a background thread (OverlayService.processBitmapForOCR, :1099),
    // while start/stop/doFrame run on the main thread, so the flags must be
    // visible across threads even if a future caller stops from a worker.
    @Volatile
    private var choreographer: Choreographer? = null
    private val pendingUpdates = mutableListOf<() -> Unit>()
    @Volatile
    private var isRunning = false

    // CRL-D4: @Synchronized makes the idempotence check-and-set atomic —
    // two concurrent start() calls can no longer both pass the guard and
    // post the callback twice.
    @Synchronized
    fun start() {
        if (isRunning) return
        choreographer = Choreographer.getInstance()
        isRunning = true
        choreographer?.postFrameCallback(this)
    }

    @Synchronized
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
        try {
            val updatesToRun = synchronized(pendingUpdates) {
                val copy = pendingUpdates.toList()
                pendingUpdates.clear()
                copy
            }
            for (update in updatesToRun) {
                try {
                    update()
                } catch (e: Throwable) {
                    // CRL-D1: the log call itself must never kill the loop
                    // or skip the rest of this frame's batch.
                    try {
                        RuntimeLogger.log("ChoreographerRenderLoop dropped failing overlay update: ${e.javaClass.simpleName}: ${e.message}", "ChoreographerRenderLoop")
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            // CRL-D1: re-post in finally. Previously any Throwable escaping
            // the loop above (only possible from the log call in the catch)
            // skipped this line and the loop died silently forever — no
            // further overlay update ever ran, and nothing restarts it.
            // The choreographer-null path still stops the loop: stop()
            // nulls choreographer, so this is a no-op after stop().
            choreographer?.postFrameCallback(this)
        }
    }
}
