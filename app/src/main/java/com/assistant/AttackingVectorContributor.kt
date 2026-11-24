package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*





import kotlin.math.hypot






















object AttackingVectorContributor:GameplayContributor{
  override val engineName="AttackingVector"
  override val capabilities=setOf(EngineCapability.ATTACK)
  private const val MAX_SHOT_RANGE=900f
  private const val MIN_AUTHORITY=0.50f
  override fun contribute(frame:RuntimeFrame):EngineContribution?{
    if(!frame.trusted||!frame.hasBall)return null
    val goalDetected=frame.goalDetected&&frame.goalConfidence>0.25f&&frame.goalRightX>frame.goalLeftX
    // BLIND-SHOT GATE: no detected goal, no shot. The synthetic-goal
    // fallback (invent a goal at the screen edge) is removed — a SHOT
    // fires only at a real detected goal.
    if(!goalDetected)return null
    val gkX:Float;val gkY:Float;val lpX:Float;val lpY:Float;val rpX:Float;val rpY:Float
    val keeper=ShotTargetMath.keeperPosition(frame.goalkeeperVisible,frame.goalkeeperX,frame.goalkeeperY,frame.goalCenterX,frame.goalCenterY);gkX=keeper.x;gkY=keeper.y;lpX=frame.goalLeftX;lpY=frame.goalTopY;rpX=frame.goalRightX;rpY=frame.goalBottomY
    val dist=hypot((frame.ballX-gkX).toDouble(),(frame.ballY-gkY).toDouble()).toFloat()
    val point=CriticalAttackingVectorEngine.computeAbsoluteScoringVector(frame.ballX,frame.ballY,gkX,gkY,lpX,lpY,rpX,rpY)
    val proximity=(1f-dist/MAX_SHOT_RANGE).coerceIn(0f,1f)
    val clearance=(1f-frame.defenderDensity*0.4f).coerceIn(0f,1f)
    val authority=(MIN_AUTHORITY+proximity*0.35f+clearance*0.10f).coerceIn(MIN_AUTHORITY,0.95f)
    // Q-C3-1 (F07): shot duration is type-correct via ShotTypeEngine —
    // POWER 35 / FINESSE 30 / CHIP 25; 35L fallback when select() returns null.
    return EngineContribution(engineName,ActionClass.SHOT,point.x.coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),point.y.coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),authority,frame.confidence,ShotTypeEngine.select(frame,point.x,point.y)?.durationHintMs?:35L)
  }
}

/* ========
BallRetentionShieldContributor
======== */
