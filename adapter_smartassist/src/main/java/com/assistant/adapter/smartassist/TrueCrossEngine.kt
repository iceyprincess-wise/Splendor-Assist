package com.assistant.adapter.smartassist
import kotlin.math.hypot
data class TrueCrossResult(val targetX:Float,val targetY:Float,val receiverPredictedX:Float,val receiverPredictedY:Float,val confidence:Float)
object TrueCrossEngine {
    private const val CROSS_BALL_SPEED_PX_S=720f; private const val ARRIVAL_BUFFER_S=0.06f
    private const val MAX_CROSS_DIST=900f; private const val BOX_PROXIMITY_PX=230f
    fun compute(ballX:Float,ballY:Float,receiverX:Float,receiverY:Float,receiverVx:Float,receiverVy:Float,goalCenterX:Float,goalCenterY:Float,laneScore:Float):TrueCrossResult?{
        val dist=hypot((receiverX-ballX).toDouble(),(receiverY-ballY).toDouble()).toFloat()
        if(dist>MAX_CROSS_DIST||laneScore<0.04f) return null
        val travelS=(dist/CROSS_BALL_SPEED_PX_S+ARRIVAL_BUFFER_S).coerceIn(0f,0.60f); val fps=60f
        val predX=(receiverX+receiverVx*fps*travelS).coerceIn(0f,1650f)
        val predY=(receiverY+receiverVy*fps*travelS).coerceIn(0f,720f)
        val dPred=hypot((predX-goalCenterX).toDouble(),(predY-goalCenterY).toDouble()).toFloat()
        val tx:Float; val ty:Float
        if(dPred<BOX_PROXIMITY_PX){tx=predX;ty=predY}
        else{val fpx=(goalCenterX-170f).coerceIn(0f,1650f);tx=(predX*0.58f+fpx*0.42f).coerceIn(0f,1650f);ty=(predY*0.58f+goalCenterY*0.42f).coerceIn(0f,720f)}
        return TrueCrossResult(tx,ty,predX,predY,(laneScore*0.78f+(1f-dist/MAX_CROSS_DIST)*0.22f).coerceIn(0f,1f))
    }
}
