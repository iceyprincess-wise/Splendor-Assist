package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/** Zero-delay every-frame intercept. Leads carrier by 2 frames. Authority=1.0. */
object InstantInterceptEngine {

    private const val SCREEN_W          = 1650f
    private const val SCREEN_H          = 720f
    private const val LOOK_AHEAD_FRAMES = 2f

    data class InterceptResult(
        val found: Boolean, val targetX: Float=0f, val targetY: Float=0f,
        val authority: Float=0f, val distanceToTarget: Float=Float.MAX_VALUE)

    fun compute(frame: RuntimeFrame): InterceptResult {
        if (frame.hasBall || !frame.trusted || frame.confidence<=0f) return InterceptResult(false)

        val ownership = try { Phase3WorldStateStore.current().ownership }
                        catch(_:Throwable) { return ballFallback(frame) }

        if (!ownership.hasOwner || ownership.owner==null) return ballFallback(frame)
        val c = ownership.owner
        if (c.isUserTeam) return ballFallback(frame)

        val px = (c.x + c.velocityX*LOOK_AHEAD_FRAMES).coerceIn(0f,SCREEN_W)
        val py = (c.y + c.velocityY*LOOK_AHEAD_FRAMES).coerceIn(0f,SCREEN_H)
        return InterceptResult(true, px, py, 1.0f,
            hypot((px-frame.ballX).toDouble(),(py-frame.ballY).toDouble()).toFloat())
    }

    private fun ballFallback(f: RuntimeFrame): InterceptResult {
        if (f.ballX<=0f && f.ballY<=0f) return InterceptResult(false)
        return InterceptResult(true, f.ballX, f.ballY, 1.0f, 0f)
    }
}
