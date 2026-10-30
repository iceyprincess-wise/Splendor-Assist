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
    /* SPLENDOR_CAPTURE_TO_SCREEN_V1 - this branch used to test frame.ballX>=825f
       against a logical 1650x720 landscape pitch, but frame.ballX is measured
       on the capture buffer (e.g. 288x640). 825 was therefore unreachable and
       the engine always picked the LEFT goal, so every shot contribution was
       aimed at the wrong end. The decision is now made in the frame's space. */
    else if(frame.ballX>=com.assistant.vision.CameraProfile.captureWidthOrFallback()*0.5f){gkX=com.assistant.vision.CameraProfile.captureWidthOrFallback()-30f;gkY=com.assistant.vision.CameraProfile.captureHeightOrFallback()*0.5f;lpX=com.assistant.vision.CameraProfile.captureWidthOrFallback()-30f;lpY=com.assistant.vision.CameraProfile.captureHeightOrFallback()*0.39f;rpX=com.assistant.vision.CameraProfile.captureWidthOrFallback()-30f;rpY=com.assistant.vision.CameraProfile.captureHeightOrFallback()*0.61f}
    else{gkX=30f;gkY=com.assistant.vision.CameraProfile.captureHeightOrFallback()*0.5f;lpX=30f;lpY=com.assistant.vision.CameraProfile.captureHeightOrFallback()*0.39f;rpX=30f;rpY=com.assistant.vision.CameraProfile.captureHeightOrFallback()*0.61f}
    val dist=hypot((frame.ballX-gkX).toDouble(),(frame.ballY-gkY).toDouble()).toFloat()
    // UNCONDITIONAL AGGRESSIVE EXECUTION: Allow shot calculation at all ranges for recovery
    val point=CriticalAttackingVectorEngine.computeAbsoluteScoringVector(frame.ballX,frame.ballY,gkX,gkY,lpX,lpY,rpX,rpY)
    val proximity=(1f-dist/MAX_SHOT_RANGE).coerceIn(0f,1f)
    val clearance=(1f-frame.defenderDensity*0.4f).coerceIn(0f,1f)
    val authority=(MIN_AUTHORITY+proximity*0.35f+clearance*0.10f).coerceIn(MIN_AUTHORITY,0.95f)
    return EngineContribution(engineName,ActionClass.SHOT,point.x.coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),point.y.coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),authority,frame.confidence,35L)
  }
}
/* ======
AttackingVectorContributor Anchor
====== */

/* ========
BallRetentionShieldContributor
======== */
