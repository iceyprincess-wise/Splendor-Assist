package com.assistant


import android.accessibilityservice.GestureDescription






















import android.view.Choreographer












import com.assistant.runtime.*


























import kotlin.math.*




























class VsyncInputAnchor(
    private val executor:(GestureDescription)->Unit
):Choreographer.FrameCallback{

    private var pending:GestureDescription?=null

    fun queue(gesture:GestureDescription){
        pending=gesture
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos:Long){
        val gesture=pending ?: return
        pending=null
        executor.invoke(gesture)
    }
}
