package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame

/**
 * 3-in-1 shield while user has ball:
 *   A) Anti-interception — lean toward safest zone / passing lane
 *   B) Stumble resistance — fires EVERY frame, zero empty arbitration windows
 *   C) Bully resistance  — authority 0.80->1.0 rising with opponent density
 */
object BallRetentionShieldEngine {

    private const val SCREEN_W = 1650f
    private const val SCREEN_H = 720f

    data class RetentionResult(
        val found: Boolean, val shieldX: Float=0f, val shieldY: Float=0f,
        val authority: Float=0f, val interceptionRisk: Float=0f)

    fun compute(frame: RuntimeFrame): RetentionResult {
        if (!frame.hasBall || !frame.trusted || frame.confidence<=0f) return RetentionResult(false)

        val bx = frame.ballX; val by = frame.ballY
        val shieldX: Float; val shieldY: Float

        if (frame.viableLaneCount>0 && (frame.passTargetX>0f || frame.passTargetY>0f)) {
            shieldX = (bx + (frame.passTargetX-bx)*0.60f).coerceIn(0f,SCREEN_W)
            shieldY = (by + (frame.passTargetY-by)*0.60f).coerceIn(0f,SCREEN_H)
        } else {
            val z = frame.zones
            val ll=z.leftTheirs.toFloat(); val ml=z.midTheirs.toFloat(); val rl=z.rightTheirs.toFloat()
            val mn=minOf(ll,ml,rl)
            shieldX = when { ll==mn -> SCREEN_W*0.15f; rl==mn -> SCREEN_W*0.85f; else -> SCREEN_W*0.50f }
            shieldY = (by-20f).coerceIn(0f,SCREEN_H)
        }

        val authority = when {
            frame.defenderDensity > 0.65f -> 1.0f
            frame.defenderDensity > 0.50f -> 0.90f
            else                          -> 0.80f
        }
        val risk = if (frame.laneCount>0)
            1f-(frame.viableLaneCount.toFloat()/frame.laneCount.toFloat())
        else frame.defenderDensity

        return RetentionResult(true, shieldX, shieldY, authority, risk.coerceIn(0f,1f))
    }
}
