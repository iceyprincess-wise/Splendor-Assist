package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object TrueCrossEngine {
    private const val CROSS_BALL_SPEED_PX_S=720f; private const val ARRIVAL_BUFFER_S=0.06f
    private const val MAX_CROSS_DIST=900f; private const val BOX_PROXIMITY_PX=230f
    // 6f: receiver velocity arrives in px/s (measured, SceneTracker via
    // PlayerMotionProducer.perSecond) — no guessed frame-rate factor.
    // The old code multiplied a px/frame velocity by a guessed 60,
    // over-leading the receiver 3x at the 20 fps processed rate.
    fun compute(ballX:Float,ballY:Float,receiverX:Float,receiverY:Float,receiverVxPerSecond:Float,receiverVyPerSecond:Float,goalCenterX:Float,goalCenterY:Float,laneScore:Float):TrueCrossResult?{
        val dist=hypot((receiverX-ballX).toDouble(),(receiverY-ballY).toDouble()).toFloat()
        if(dist>MAX_CROSS_DIST||laneScore<0.04f) return null
        val travelS=(dist/CROSS_BALL_SPEED_PX_S+ARRIVAL_BUFFER_S).coerceIn(0f,0.60f)
        val predX=(receiverX+receiverVxPerSecond*travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        val predY=(receiverY+receiverVyPerSecond*travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        val dPred=hypot((predX-goalCenterX).toDouble(),(predY-goalCenterY).toDouble()).toFloat()
        val tx:Float; val ty:Float
        if(dPred<BOX_PROXIMITY_PX){tx=predX;ty=predY}
        else{val sw=com.assistant.vision.CameraProfile.captureWidthOrFallback();val sh=com.assistant.vision.CameraProfile.captureHeightOrFallback();val fpx=(goalCenterX-170f).coerceIn(0f,sw);tx=(predX*0.58f+fpx*0.42f).coerceIn(0f,sw);ty=(predY*0.58f+goalCenterY*0.42f).coerceIn(0f,sh)}
        return TrueCrossResult(tx,ty,(laneScore*0.78f+(1f-dist/MAX_CROSS_DIST)*0.22f).coerceIn(0f,1f))
    }
}

/* ========
TrueShotEngine
======== */
