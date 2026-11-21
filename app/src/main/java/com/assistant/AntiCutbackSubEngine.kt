package com.assistant




























import com.assistant.diagnostic.RuntimeLogger




import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource


import com.assistant.runtime.*


























import kotlin.math.*


import kotlin.math.atan2

import kotlin.math.cos







import kotlin.math.sin















// Letter A item 23: the LatencyDefeatingInputEngine constructor parameter
// is REMOVED (was: class AntiCutbackSubEngine(private val inputEngine:
// LatencyDefeatingInputEngine)). It was never referenced in the file body —
// the engine offers through ContributionRegistry, it never injects a swipe
// directly — so the parameter was dead. Sole call site updated:
// ActiveGestureController.kt:122 now constructs AntiCutbackSubEngine().
class AntiCutbackSubEngine(){
  companion object{@Volatile private var lastExecutionTimestamp=0L;private const val DEBOUNCE_COOLDOWN_MS=20L}
  fun blockCutbackPassingLanes(wingerX:Float,wingerY:Float,myNearestDefenderX:Float,myNearestDefenderY:Float,penaltyBoxCenterX:Float,penaltyBoxCenterY:Float,joystickX:Float,joystickY:Float,screenWidth:Float=com.assistant.vision.CameraProfile.captureWidthOrFallback(),screenHeight:Float=com.assistant.vision.CameraProfile.captureHeightOrFallback()):Boolean{
    val now=System.currentTimeMillis()
    // No64-D30 (F9): the Interception switch gates this INT emergency offer.
    if(!com.assistant.overlay.interceptor.InterceptionRuntimeRegistry.enabled||!com.assistant.overlay.interceptor.InterceptionRuntimeRegistry.autoIntercept)return false
    if(now-lastExecutionTimestamp<DEBOUNCE_COOLDOWN_MS)return false
    val baselineThreshold=screenHeight*0.55f
    val leftTouchlineThreshold=screenWidth*0.15f
    val rightTouchlineThreshold=screenWidth*0.85f
    val isWingerAtBaseline=wingerY>baselineThreshold&&(wingerX<leftTouchlineThreshold||wingerX>rightTouchlineThreshold)
    if(!isWingerAtBaseline)return false
    val depthRange=(screenHeight-baselineThreshold).coerceAtLeast(1f)
    val depthFactor=((wingerY-baselineThreshold)/depthRange).coerceIn(0f,1f)
    val wingerWeight=0.35f-(depthFactor*0.15f);val centerWeight=1.0f-wingerWeight
    val laneMidpointX=(wingerX*wingerWeight)+(penaltyBoxCenterX*centerWeight)
    val laneMidpointY=(wingerY*wingerWeight)+(penaltyBoxCenterY*centerWeight)
    val targetAngle=atan2((laneMidpointY-myNearestDefenderY).toDouble(),(laneMidpointX-myNearestDefenderX).toDouble())
    val swipeRadius=screenHeight*0.22f
    val runX=(joystickX+(cos(targetAngle)*swipeRadius).toFloat()).coerceIn(0f,screenWidth)
    val runY=(joystickY+(sin(targetAngle)*swipeRadius).toFloat()).coerceIn(0f,screenHeight)
    val request=ExecutionRequest(source=ExecutionSource.INTERCEPTION,phase=4,actionClass=com.assistant.runtime.ActionClass.DEFEND,engine="AntiCutbackSubEngine",startX=joystickX,startY=joystickY,endX=runX,endY=runY,duration=18L)
    val submitted=ContributionRegistry.offer(request)
    if(submitted){lastExecutionTimestamp=now;RuntimeLogger.log("ANTI_CUTBACK lane blocked vector=(\$runX,\$runY)","DEFENSE");return true}
    return false
  }
}

/* ========
AuthorityArbitrationEngine
======== */
