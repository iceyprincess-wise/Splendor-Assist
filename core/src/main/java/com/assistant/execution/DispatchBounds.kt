package com.assistant.execution

// N5: live capture bounds for the dispatch choke point.
//
// HybridExecutionTerminal.route() (this module) must clamp every dispatch
// request to the capture frame, but the capture size lives in the app
// module (CameraProfile, com.assistant.vision) and core cannot depend on
// app. So the app publishes the live bounds here on every
// recreateCaptureSurfaces() and route() reads them from here. The holder
// is deliberately dumb: 0x0 means "capture not yet established" and
// route() then passes the request through unchanged (it must not invent
// bounds — the unset-capture fallback geometry is Q62-1's open question).
// @Volatile: written on the capture thread, read on dispatch threads.
object DispatchBounds {
    @Volatile var captureWidth: Float = 0f
    @Volatile var captureHeight: Float = 0f

    /**
     * L11 MaxDragProbe: the measured joystick drag radius in pixels.
     * Null = UNMEASURED (no probe has completed on this device yet).
     * The trainer probe writes it; NOTHING consumes it yet — the clamp
     * must not be re-tuned to an invented number. Once Ed runs the probe
     * on his device and a real radius lands here, the clamp follow-up
     * wires it in. setMaxDrag drops non-positive / non-finite values so
     * a bad probe can never arm the clamp.
     */
    @Volatile var maxDragPx: Float? = null

    fun setMaxDrag(px: Float?) {
        maxDragPx = px?.takeIf { it > 0f && it.isFinite() }
    }

    fun set(width: Float, height: Float) {
        captureWidth = width
        captureHeight = height
    }

    fun isSet(): Boolean = captureWidth > 0f && captureHeight > 0f
}
