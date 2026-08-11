package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.AgilityEngine
import com.assistant.adapter.smartassist.Phase3WorldStateStore
import com.assistant.runtime.*
import kotlin.math.atan2
object AgilityContributor:GameplayContributor{
  override val engineName="Agility"
  override val capabilities=setOf(EngineCapability.MOVEMENT,EngineCapability.SUPPORT)
  override fun contribute(frame:RuntimeFrame):EngineContribution?{
    if(!frame.trusted||!frame.hasBall)return null
    val opponentDistance=try{val def=Phase3WorldStateStore.current().defender
      if(def.found&&def.distanceToAttacker<Float.MAX_VALUE)def.distanceToAttacker.coerceIn(0f,1200f)
      else(1f-frame.defenderDensity)*500f}catch(_:Throwable){(1f-frame.defenderDensity)*500f}
    val movementAngle=if(frame.passTargetX>0f||frame.passTargetY>0f)
      Math.toDegrees(atan2((frame.passTargetY-frame.ballY).toDouble(),(frame.passTargetX-frame.ballX).toDouble())).toFloat()
    else 0f
    val result=AgilityEngine.computeAgility(frame.bestLaneConfidence*10f,opponentDistance,movementAngle,frame.confidence,frame.defenderDensity.coerceIn(0f,1f))
    val authority=(result.stabilityBoost/10f).coerceIn(0f,1f).coerceAtLeast(0.40f)
    val targetX=if(frame.viableLaneCount>0&&frame.passTargetX>0f)frame.passTargetX else(frame.ballX+80f).coerceIn(0f,1650f)
    val targetY=if(frame.viableLaneCount>0&&frame.passTargetY>0f)frame.passTargetY else frame.ballY
    return EngineContribution(engineName,ActionClass.MOVE,targetX,targetY,authority,frame.confidence,result.shieldDurationMs.coerceIn(16L,80L))
  }
}
