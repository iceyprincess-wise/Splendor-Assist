package com.assistant.adapter.smartassist
data class AgilityResult(val shieldActive:Boolean,val stabilityBoost:Float,val controlRetentionBoost:Float,val turnAssist:Float,val shieldAngleDegrees:Float,val shieldDurationMs:Long)
object AgilityEngine{
  private const val EXPECTED_MAX_VELOCITY=1.0f
  fun computeAgility(playerVelocity:Float,opponentDistance:Float,movementAngleDegrees:Float,possessionConfidence:Float,turnIntensity:Float,playerX:Float?=null,playerY:Float?=null,oppX:Float?=null,oppY:Float?=null):AgilityResult{
    val proximity=(1.0f-(opponentDistance/220.0f)).coerceIn(0.0f,1.0f)
    val speed=(playerVelocity/EXPECTED_MAX_VELOCITY).coerceIn(0.0f,1.0f)
    val confidence=possessionConfidence.coerceIn(0.0f,1.0f)
    val shieldActive=ShieldAssistEngine.shouldEngageShield(playerVelocity,opponentDistance)
    val stabilityBoost:Float=when{
      shieldActive->(4.0f+proximity*6.0f+speed*3.0f+confidence*2.0f).coerceIn(4.0f,15.0f)
      opponentDistance in 1f..500f->{val softP=(1.0f-opponentDistance/500f).coerceIn(0.0f,1.0f);(3.0f+softP*4.0f*confidence).coerceIn(3.0f,15.0f)}
      confidence>0f->3.0f
      else->0.0f}
    val controlRetentionBoost=if(confidence>0.1f)(confidence*0.6f+proximity*0.4f).coerceIn(0.0f,1.0f) else 0.0f
    val turnAssist=if(turnIntensity>0.10f)(turnIntensity*0.7f+proximity*0.3f)*confidence else 0.0f
    val shieldAngle=if(playerX!=null&&playerY!=null&&oppX!=null&&oppY!=null) ShieldAssistEngine.shieldAngle(playerX,playerY,oppX,oppY) else ShieldAssistEngine.shieldAngle(movementAngleDegrees)
    val shieldDuration=if(opponentDistance>0f) ShieldAssistEngine.shieldHoldDuration(playerVelocity,opponentDistance) else ShieldAssistEngine.shieldHoldDuration()
    return AgilityResult(shieldActive,stabilityBoost,controlRetentionBoost,turnAssist.coerceIn(0.0f,1.0f),shieldAngle,shieldDuration)
  }
}
