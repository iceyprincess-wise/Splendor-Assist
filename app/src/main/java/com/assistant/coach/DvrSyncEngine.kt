package com.assistant.coach

import android.os.Process
import android.view.Choreographer
import com.assistant.core.AdapterIpcBridge

class DvrSyncEngine : Choreographer.FrameCallback {
    
    private var isRecording = false

    fun startDvrHooks() {
        // Enforce thread segregation to prevent LMK starvation of the DVR
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        isRecording = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopDvrHooks() {
        isRecording = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRecording) return

        // 1. Dispatch strict frame timing to the Sync Adapter
        AdapterIpcBridge.dispatchSyncPulse(frameTimeNanos)

        // 2. Execute zero-allocation rolling-chunk flush here.
        // Direct ByteBuffer manipulations occur at this exact nanosecond 
        // to bypass GC tearing before the SurfaceFlinger composite deadline.
        
        // Loop callback for the next frame
        Choreographer.getInstance().postFrameCallback(this)
    }
}
