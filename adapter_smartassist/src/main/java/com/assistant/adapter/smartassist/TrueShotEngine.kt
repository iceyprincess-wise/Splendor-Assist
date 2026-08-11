package com.assistant.adapter.smartassist
import kotlin.math.hypot
data class TrueShotResult(val targetX:Float,val targetY:Float,val authority:Float,val onTarget:Boolean)
object TrueShotEngine {
    private const val MAX_SHOT_DIST=700f; private const val MIN_SHOT_DIST=25f
    fun compute(ballX:Float,ballY:Float,goalLeftX:Float,goalRightX:Float,goalTopY:Float,goalBottomY:Float,goalkeeperX:Float,goalkeeperVisible:Boolean,defenderDensity:Float,goalDetected:Boolean):TrueShotResult?{
        val goalCX=if(goalDetected)(goalLeftX+goalRightX)*0.5f else 1650f
        val goalCY=if(goalDetected)(goalTopY+goalBottomY)*0.5f else ballY
        val dist=hypot((ballX-goalCX).toDouble(),(ballY-goalCY).toDouble()).toFloat()
        if(dist>MAX_SHOT_DIST||dist<MIN_SHOT_DIST) return null
        val openX:Float; val openY:Float=goalCY
        if(goalDetected&&goalkeeperVisible&&goalkeeperX>0f){
            val mid=(goalLeftX+goalRightX)*0.5f
            openX=if(goalkeeperX<=mid)(goalCX+(goalRightX-goalCX)*0.72f).coerceIn(goalLeftX,goalRightX)
                  else (goalCX-(goalCX-goalLeftX)*0.72f).coerceIn(goalLeftX,goalRightX)
        } else { openX=goalCX }
        val proximity=1f-(dist/MAX_SHOT_DIST)
        return TrueShotResult(openX.coerceIn(0f,1650f),openY.coerceIn(0f,720f),(proximity*0.70f+(1f-defenderDensity*0.35f).coerceIn(0f,1f)*0.30f).coerceIn(0f,1f),goalDetected)
    }
}
