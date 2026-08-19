package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.CriticalAttackingVectorEngine
import com.assistant.runtime.*
import kotlin.math.hypot
object AttackingVectorContributor:GameplayContributor{
  override val engineName="AttackingVector"
  override val capabilities=setOf(EngineCapability.ATTACK)
  private const val MAX_SHOT_RANGE=900f
  private const val MIN_AUTHORITY=0.50f
  override fun contribute(frame:RuntimeFrame):EngineContribution?{
    if(!frame.trusted||!frame.hasBall)return null
    val goalDetected=frame.goalDetected&&frame.goalConfidence>0.25f&&frame.goalRightX>frame.goalLeftX
    val gkX:Float;val gkY:Float;val lpX:Float;val lpY:Float;val rpX:Float;val rpY:Float
    if(goalDetected){gkX=(frame.goalLeftX+frame.goalRightX)*0.5f;gkY=(frame.goalTopY+frame.goalBottomY)*0.5f;lpX=frame.goalLeftX;lpY=frame.goalTopY;rpX=frame.goalRightX;rpY=frame.goalBottomY}
    else if(frame.ballX>=825f){gkX=1620f;gkY=360f;lpX=1620f;lpY=280f;rpX=1620f;rpY=440f}
    else{gkX=30f;gkY=360f;lpX=30f;lpY=280f;rpX=30f;rpY=440f}
    val dist=hypot((frame.ballX-gkX).toDouble(),(frame.ballY-gkY).toDouble()).toFloat()
    if(dist>MAX_SHOT_RANGE)return null
    val point=CriticalAttackingVectorEngine.computeAbsoluteScoringVector(frame.ballX,frame.ballY,gkX,gkY,lpX,lpY,rpX,rpY)
    val proximity=(1f-dist/MAX_SHOT_RANGE).coerceIn(0f,1f)
    val clearance=(1f-frame.defenderDensity*0.4f).coerceIn(0f,1f)
    val authority=(MIN_AUTHORITY+proximity*0.35f+clearance*0.10f).coerceIn(MIN_AUTHORITY,0.95f)
    return EngineContribution(engineName,ActionClass.SHOT,point.x.coerceIn(0f,1650f),point.y.coerceIn(0f,720f),authority,frame.confidence,35L)
  }
}
