package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame

/** Presses carrier's CURRENT position — arrive NOW before they play the pass. Authority=1.0. */
object BuildUpPressEngine {

    private const val SCREEN_W = 1650f
    private const val SCREEN_H = 720f

    data class PressResult(
        val found: Boolean, val targetX: Float=0f,
        val targetY: Float=0f, val authority: Float=0f)

    fun compute(frame: RuntimeFrame): PressResult {
        if (frame.hasBall || !frame.trusted || frame.confidence<=0f) return PressResult(false)

        val ownership = try { Phase3WorldStateStore.current().ownership }
                        catch(_:Throwable) { return ballFallback(frame) }

        if (!ownership.hasOwner || ownership.owner==null) return ballFallback(frame)
        val c = ownership.owner!!
        if (c.isUserTeam) return ballFallback(frame)

        return PressResult(true, c.x.coerceIn(0f,SCREEN_W), c.y.coerceIn(0f,SCREEN_H), 1.0f)
    }

    private fun ballFallback(f: RuntimeFrame): PressResult {
        if (f.ballX<=0f && f.ballY<=0f) return PressResult(false)
        return PressResult(true, f.ballX, f.ballY, 1.0f)
    }
}
