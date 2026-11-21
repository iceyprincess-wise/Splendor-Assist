package com.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription










import android.graphics.Path










import android.util.Log













import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object ActiveAttackerEngine{

  // Letter A item 8: getAttackerDiagnostics() and its
  // AttackerActivationDiagnostics surface deleted — zero callers, so
  // the five diagnostics fields (totalComputes, lastConfidence,
  // lastTargetX, lastTargetY, lastUpdatedMs) were written and never
  // read. The per-compute counter writes inside the 5-arg overload go
  // with them. The 5-arg overload itself is NOT deleted: it is the
  // batch-1 L6 direct-execute bypass, PINNED by the batch-1 harness
  // as caller-less — see its comment below.

  // L6 (batch 1, DECIDED not-needed): this 5-arg overload is the
  // DIRECT-EXECUTE bypass — it dispatches an unclamped gesture via
  // GestureExecutionAuthority.execute, bypassing
  // HybridExecutionTerminal.route() (DispatchBounds clamp + gates). It
  // has ZERO callers: the only call site (VisionCore:209) uses the 2-arg
  // pure-compute overload below. Do NOT wire it into the live path:
  // wiring it would re-open the ghost-touch vector batch G closed. Kept
  // (not deleted) per the no-delete rule; the batch-1 harness pins its
  // caller-less state. The choke point is already single-route for all
  // live traffic — L6 is closed as not-needed, not implemented.
  fun compute(service:AccessibilityService,currentX:Float,currentY:Float,scene:SceneSnapshot,possession:BallPossessionResult):ActiveAttackerResult{
    if(!possession.hasPossession)return ActiveAttackerResult(found=false)
    val index=possession.ownerIndex
    if(index !in scene.trackedPlayers.indices)return ActiveAttackerResult(found=false)
    val player=scene.trackedPlayers[index]
    if(!player.isUserTeam)return ActiveAttackerResult(found=false)
    // 6a: the !isUserTeam return above guarantees the owner is the user team here.
    AttackAxisModel.observe(scene,possession.hasPossession,com.assistant.vision.CameraProfile.captureWidthOrFallback())
    val result=ActiveAttackerResult(true,player,index)
    // Letter A item 9: the gate reads possession.confidence directly —
    // the value the result used to carry in its deleted confidence
    // field, so the 0.30f threshold is unchanged.
    if(result.found&&possession.confidence>0.30f){
      try{
        val worldState=try{Phase3WorldStateStore.current()}catch(_:Throwable){null}
        val bestLane=worldState?.passingGraph?.lanes?.filter{!it.blocked}?.maxByOrNull{it.score}
        val goalX:Float;val goalY:Float
        if(scene.goalDetected&&scene.goalRightX>scene.goalLeftX){goalX=(scene.goalLeftX+scene.goalRightX)*0.5f;goalY=(scene.goalTopY+scene.goalBottomY)*0.5f}
        else{
          val sw=com.assistant.vision.CameraProfile.captureWidthOrFallback()
          val sh=com.assistant.vision.CameraProfile.captureHeightOrFallback()
          val inferred=AttackAxisModel.directionIfConfident()
          val fallback=if(player.x>=sw*0.5f)sw-30f else 30f
          goalX=if(inferred>0)sw-30f else if(inferred<0)30f else fallback
          goalY=sh*0.5f
        }
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
        // F37-D2: the execute() result was discarded — a refusal here left
        // no trace at this call site.
        val dispatched = GestureExecutionAuthority.execute(service,gesture,null,null)
        if (!dispatched) Log.w("ActiveAttackerEngine", "Directed push dispatch refused (see SMART_ASSIST gate log)")
      }catch(e:Exception){Log.e("ActiveAttackerEngine","Directed push skipped: ${e.message}")}
    }
    return result
  }

  fun compute(scene:SceneSnapshot,possession:BallPossessionResult):ActiveAttackerResult{
    if(!possession.hasPossession)return ActiveAttackerResult(found=false)
    val index=possession.ownerIndex
    if(index !in scene.trackedPlayers.indices)return ActiveAttackerResult(found=false)
    val player=scene.trackedPlayers[index]
    if(!player.isUserTeam)return ActiveAttackerResult(found=false)
    return ActiveAttackerResult(true,player,index)
  }
}

/* ========
ActiveAttackerResult
======== */
