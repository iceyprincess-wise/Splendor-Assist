package com.assistant.adapter.smartassist
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlin.math.hypot
object ActiveAttackerEngine{
  data class AttackerActivationDiagnostics(val totalComputes:Long,val lastConfidence:Float,val lastTargetX:Float,val lastTargetY:Float,val lastUpdatedMs:Long)
  @Volatile private var computeCalls=0L;@Volatile private var lastConfidence=0f
  @Volatile private var lastTargetX=0f;@Volatile private var lastTargetY=0f;@Volatile private var lastUpdatedMs=0L
  @Synchronized fun getAttackerDiagnostics()=AttackerActivationDiagnostics(computeCalls,lastConfidence,lastTargetX,lastTargetY,lastUpdatedMs)

  fun compute(service:AccessibilityService,currentX:Float,currentY:Float,scene:SceneSnapshot,possession:BallPossessionResult):ActiveAttackerResult{
    if(!possession.hasPossession)return ActiveAttackerResult(found=false)
    val index=possession.ownerIndex
    if(index !in scene.trackedPlayers.indices)return ActiveAttackerResult(found=false)
    val player=scene.trackedPlayers[index]
    val result=ActiveAttackerResult(true,player,index,possession.confidence)
    if(result.found&&result.confidence>0.30f){
      try{
        val worldState=try{Phase3WorldStateStore.current()}catch(_:Throwable){null}
        val bestLane=worldState?.passingGraph?.lanes?.filter{!it.blocked}?.maxByOrNull{it.score}
        val goalX:Float;val goalY:Float
        if(scene.goalDetected&&scene.goalRightX>scene.goalLeftX){goalX=(scene.goalLeftX+scene.goalRightX)*0.5f;goalY=(scene.goalTopY+scene.goalBottomY)*0.5f}
        else{goalX=if(player.x>=825f)1620f else 30f;goalY=360f}
        val targetX:Float;val targetY:Float
        if(bestLane!=null){targetX=bestLane.receiver.x;targetY=bestLane.receiver.y}
        else{targetX=goalX;targetY=goalY}
        val dx=targetX-player.x;val dy=targetY-player.y
        val mag=hypot(dx.toDouble(),dy.toDouble()).toFloat()
        val R=50f;val endX:Float;val endY:Float
        if(mag>1f){endX=currentX+(dx/mag)*R;endY=currentY+(dy/mag)*R}else{endX=currentX+R;endY=currentY}
        val path=Path().apply{moveTo(currentX,currentY);lineTo(endX,endY)}
        val stroke=GestureDescription.StrokeDescription(path,0L,20L)
        val gesture=GestureDescription.Builder().addStroke(stroke).build()
        synchronized(this){computeCalls++;lastConfidence=result.confidence;lastTargetX=targetX;lastTargetY=targetY;lastUpdatedMs=System.currentTimeMillis()}
        GestureExecutionAuthority.execute(service,gesture,null,null)
      }catch(e:Exception){Log.e("ActiveAttackerEngine","Directed push skipped: \${e.message}")}
    }
    return result
  }

  fun compute(scene:SceneSnapshot,possession:BallPossessionResult):ActiveAttackerResult{
    if(!possession.hasPossession)return ActiveAttackerResult(found=false)
    val index=possession.ownerIndex
    if(index !in scene.trackedPlayers.indices)return ActiveAttackerResult(found=false)
    val player=scene.trackedPlayers[index]
    return ActiveAttackerResult(true,player,index,possession.confidence)
  }
}
