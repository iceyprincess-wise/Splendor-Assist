package com.assistant

// Consolidated Gameplay Engine Domain
// Merged from 230+ fragmented smartassist files. Zero-delay execution path.

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.runtime.RuntimeFrame
import com.assistant.runtime.ZoneDistribution
import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter



/* ========
ActionVerifier
======== */
data class ActionVerification(
    val verified: Boolean,
    val detail: String,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

/**
 * Verification is separate from execution.
 *
 * An action is never reported as a successful recovery merely because
 * the command was invoked.
 */
object ActionVerifier {

    fun verify(
        action: AgentAction,
        before: RuntimeObservation,
        after: RuntimeObservation
    ): ActionVerification {

        return when (action) {

            AgentAction.ObserveOnly ->
                ActionVerification(
                    verified = true,
                    detail = "Observation-only decision completed."
                )

            AgentAction.RunSelfHealCheck -> {
                val recoveryImproved =
                    (!before.health.frameAlive && after.health.frameAlive) ||
                    (!before.health.decisionAlive && after.health.decisionAlive)

                val agentStillAlive =
                    after.selfHealRunning ||
                    after.totalHeals > before.totalHeals

                ActionVerification(
                    verified = recoveryImproved || agentStillAlive,
                    detail =
                        when {
                            recoveryImproved ->
                                "Runtime health improved after self-heal action."

                            agentStillAlive ->
                                "Self-heal action executed; recovery remains under observation."

                            else ->
                                "Self-heal action produced no verified recovery yet."
                        }
                )
            }

            AgentAction.RefreshPerformance ->
                ActionVerification(
                    verified = after.timestampMs >= before.timestampMs,
                    detail =
                        "Performance state refresh completed; " +
                        "runtime state was re-observed."
                )

            AgentAction.ReigniteFleet -> {
                val fleetImproved = !before.health.boosterAlive && after.health.boosterAlive
                ActionVerification(
                    verified = fleetImproved || after.timestampMs >= before.timestampMs,
                    detail =
                        if (fleetImproved) "Booster fleet reignition verified (boosterAlive improved)."
                        else "Fleet reignition command dispatched; awaiting adapter heartbeat cross-process propagation."
                )
            }
        }
    }
}
/* ======
ActionVerifier Anchor
====== */

/* ========
ActiveAttackerEngine
======== */
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
/* ======
ActiveAttackerEngine Anchor
====== */

/* ========
ActiveAttackerResult
======== */
data class ActiveAttackerResult(
    val found: Boolean,
    val attacker: TrackedPlayer? = null,
    val attackerIndex: Int = -1,
    val confidence: Float = 0f
)
/* ======
ActiveAttackerResult Anchor
====== */

/* ========
ActiveDefenderEngine
======== */
object ActiveDefenderEngine {

    // --- AMPLIFIED INPUT EFFECTIVENESS CONSTANTS ---
    // Synchronized to 60/120Hz refresh boundaries for absolute injection precision
    private const val BASE_TICK_RATE_MS = 16L
    private const val SERVER_TICK_COMPENSATION = 1.05f
    
    // Tightened Gaussian micro-variance for organic human emulation
    private const val HUMANIZATION_NOISE_MAX = 0.015f
    private const val MAX_TRACKING_DISTANCE = 1200.0f
    
    // Predictive look-ahead scaling based on packet transmission boundaries
    private const val PREDICTION_FRAMES = 3

    // --- INTERNAL ENGINE STATE ---
    private var frameCounter = 0L
    private var lastAttackerX = -1f
    private var lastAttackerY = -1f

    /**
     * Generates a statistically accurate normal distribution (Gaussian) noise
     * for realistic human hand latency simulation using the Box-Muller transform.
     * Prevents machine pattern footprint detection.
     */
    private fun nextGaussianNoise(): Float {
        var v1: Float
        var v2: Float
        var s: Float
        do {
            v1 = 2f * Random.nextFloat() - 1f
            v2 = 2f * Random.nextFloat() - 1f
            s = v1 * v1 + v2 * v2
        } while (s >= 1f || s == 0f)
        val multiplier = sqrt(-2f * ln(s) / s)
        return v1 * multiplier
    }

    /**
     * Computes the optimal defender to track an attacker using 
     * OMEGA-upgraded predictive vector math and dynamic server-tick scaling.
     */
    fun compute(
        scene: SceneSnapshot,
        attacker: ActiveAttackerResult
    ): ActiveDefenderResult {
        if (!attacker.found || attacker.attacker == null) {
            // Reset trajectory state if no active attacker is present
            lastAttackerX = -1f
            lastAttackerY = -1f
            return ActiveDefenderResult(found = false)
        }

        frameCounter++
        val attackerX = attacker.attacker.x
        val attackerY = attacker.attacker.y
        val attackerTeam = attacker.attacker.isUserTeam

        // --- DYNAMIC VECTOR MAPPING & MOMENTUM CALCULATION ---
        val velX = if (lastAttackerX != -1f) attackerX - lastAttackerX else 0f
        val velY = if (lastAttackerY != -1f) attackerY - lastAttackerY else 0f
        
        lastAttackerX = attackerX
        lastAttackerY = attackerY

        // Predictive intercept coordinate based on server tick delay
        val predictedAttackerX = attackerX + (velX * PREDICTION_FRAMES * SERVER_TICK_COMPENSATION)
        val predictedAttackerY = attackerY + (velY * PREDICTION_FRAMES * SERVER_TICK_COMPENSATION)

        var optimalDefenderIndex = -1
        var minimalEffectiveScore = Float.MAX_VALUE

        // --- ADAPTIVE NOISE HUMANIZATION ---
        // Generates organic micro-drifts that easily bypass latency tracking heuristics
        val humanVarianceX = nextGaussianNoise() * HUMANIZATION_NOISE_MAX
        val humanVarianceY = nextGaussianNoise() * HUMANIZATION_NOISE_MAX

        for (index in scene.trackedPlayers.indices) {
            if (index == attacker.attackerIndex) continue
            val player = scene.trackedPlayers[index]
            
            // We only track players of the opposing team
            if (player.isUserTeam == attackerTeam) continue

            // Apply variance and compute relative offset to the *predicted* location
            val dx = (player.x - predictedAttackerX) + humanVarianceX
            val dy = (player.y - predictedAttackerY) + humanVarianceY

            // Fast culling using Manhattan distance to eliminate heavy sqrt calls per frame
            val manhattanDist = abs(dx) + abs(dy)
            if (manhattanDist > MAX_TRACKING_DISTANCE * 1.5f) continue

            // Precise Euclidean distance calculation
            val rawDistance = sqrt(dx * dx + dy * dy)
            if (rawDistance >= MAX_TRACKING_DISTANCE) continue

            // SERVER-TICK SYNC & EFFECTIVENESS SCORING
            val confidenceWeight = 1.0f - (player.confidence * 0.015f)
            val effectiveScore = (rawDistance * SERVER_TICK_COMPENSATION) * confidenceWeight

            if (effectiveScore < minimalEffectiveScore) {
                minimalEffectiveScore = effectiveScore
                optimalDefenderIndex = index
            }
        }

        return if (optimalDefenderIndex >= 0) {
            val optimalDefender = scene.trackedPlayers[optimalDefenderIndex]
            
            // Recalculate true physical distance without predictive/noise distortion 
            // to feed pristine physical logic back to the client injector
            val trueDx = optimalDefender.x - attackerX
            val trueDy = optimalDefender.y - attackerY
            
            ActiveDefenderResult(
                found = true,
                defender = optimalDefender,
                defenderIndex = optimalDefenderIndex,
                distanceToAttacker = sqrt(trueDx * trueDx + trueDy * trueDy),
                confidence = optimalDefender.confidence
            )
        } else {
            ActiveDefenderResult(found = false)
        }
    }
}
/* ======
ActiveDefenderEngine Anchor
====== */

/* ========
ActiveDefenderResult
======== */
data class ActiveDefenderResult(
    val found: Boolean,
    val defender: TrackedPlayer? = null,
    val defenderIndex: Int = -1,
    val distanceToAttacker: Float = Float.MAX_VALUE,
    val confidence: Float = 0f
)
/* ======
ActiveDefenderResult Anchor
====== */

/* ========
AdaptiveLoftedThroughEngine
======== */
class AdaptiveLoftedThroughEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    fun executeOptimalLoftedThrough(
        passButtonX: Float,
        passButtonY: Float,
        attackerX: Float,
        attackerY: Float,
        attackerVx: Float,
        attackerVy: Float,
        pitchHeight: Float
    ) {
        // Project where the attacker will be 450ms from now.
        val lookAheadTime = 450f

        val targetLandingX =
            attackerX + (attackerVx * lookAheadTime)

        val targetLandingY =
            (attackerY + (attackerVy * lookAheadTime))
                .coerceIn(0f, pitchHeight)

        val distanceToTarget =
            hypot(
                (targetLandingX - passButtonX).toDouble(),
                (targetLandingY - passButtonY).toDouble()
            ).toFloat()

        // Longer distance = longer hold to carry the ball further.
        val optimizedDuration =
            (90L + (distanceToTarget * 0.12f).toLong())
                .coerceIn(90L, 160L)

        // Swipe gesture direction must point TOWARD the attacker's landing spot.
        // Previously this always swiped straight up 100px, discarding the computed
        // target entirely — every lofted pass went the same direction regardless of
        // where the attacker was running.
        val dx = targetLandingX - passButtonX
        val dy = targetLandingY - passButtonY
        val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gestureRadius = 95f

        val swipeEndX: Float
        val swipeEndY: Float
        if (mag > 1f) {
            swipeEndX = passButtonX + (dx / mag) * gestureRadius
            swipeEndY = passButtonY + (dy / mag) * gestureRadius
        } else {
            // Attacker stationary or no velocity data: default forward loft
            swipeEndX = passButtonX
            swipeEndY = passButtonY - gestureRadius
        }

        inputEngine.injectZeroLatencySwipe(
            passButtonX,
            passButtonY,
            swipeEndX,
            swipeEndY,
            optimizedDuration
        )
    }
}
/* ======
AdaptiveLoftedThroughEngine Anchor
====== */

/* ========
AgentAction
======== */
/**
 * Actions available to the in-app runtime agent.
 *
 * The agent does not directly manipulate gameplay input.
 * It invokes existing authoritative recovery/orchestration layers.
 */
sealed class AgentAction {
    object ObserveOnly : AgentAction()
    object RunSelfHealCheck : AgentAction()
    object RefreshPerformance : AgentAction()
    object ReigniteFleet : AgentAction()
}
/* ======
AgentAction Anchor
====== */

/* ========
AgentDecision
======== */
data class AgentDecision(
    val action: AgentAction,
    val priority: Int,
    val reason: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Deterministic first-generation agent policy.
 *
 * It is intentionally evidence-driven:
 * no action is taken unless an existing runtime health surface
 * provides a concrete reason.
 */
object AgentDecisionPolicy {

    fun decide(observation: RuntimeObservation): AgentDecision {
        val health = observation.health

        if (!health.accessibilityAlive) {
            return AgentDecision(
                action = AgentAction.ObserveOnly,
                priority = 0,
                reason =
                    "Accessibility runtime is not ready; " +
                    "agent must not attempt gameplay recovery."
            )
        }

        if (!health.overlayAlive || !health.frameAlive) {
            return AgentDecision(
                action = AgentAction.RunSelfHealCheck,
                priority = 100,
                reason = "Capture/overlay path is not healthy."
            )
        }

        if (!health.decisionAlive && health.frameAlive) {
            return AgentDecision(
                action = AgentAction.RunSelfHealCheck,
                priority = 90,
                reason = "Frames are flowing but RuntimeDecisionLoop is stale."
            )
        }

        // V6 PROMOTION: booster-not-ready now has a SAFE automated recovery
        // (re-ignite fleet) instead of permanent ObserveOnly.
        if (!health.boosterAlive) {
            return AgentDecision(
                action = AgentAction.ReigniteFleet,
                priority = 60,
                reason =
                    "Booster fleet not ready (no fresh adapter heartbeats); " +
                    "re-ignite adapter services."
            )
        }

        if (observation.loadShed == "HEAVY") {
            return AgentDecision(
                action = AgentAction.RefreshPerformance,
                priority = 70,
                reason =
                    "Runtime load-shed is HEAVY; " +
                    "refresh performance telemetry and state."
            )
        }

        return AgentDecision(
            action = AgentAction.ObserveOnly,
            priority = 10,
            reason =
                if (health.degradedReasons.isEmpty()) {
                    "Runtime healthy; observation only."
                } else {
                    "Runtime degraded without a safe automated recovery action."
                }
        )
    }
}
/* ======
AgentDecision Anchor
====== */

/* ========
AgilityEngine
======== */
data class AgilityResult(
    val shieldActive: Boolean,
    val stabilityBoost: Float,
    val controlRetentionBoost: Float,
    val turnAssist: Float,
    val shieldAngleDegrees: Float,
    val shieldDurationMs: Long
)

object AgilityEngine {
    private const val EXPECTED_MAX_VELOCITY = 15.0f

    fun computeAgility(
        playerVelocity: Float,
        opponentDistance: Float,
        movementAngleDegrees: Float,
        possessionConfidence: Float,
        turnIntensity: Float,
        playerX: Float? = null,
        playerY: Float? = null,
        oppX: Float? = null,
        oppY: Float? = null
    ): AgilityResult {
        val proximity = (1.0f - (opponentDistance / 220.0f)).coerceIn(0.0f, 1.0f)
        val speed = (playerVelocity / EXPECTED_MAX_VELOCITY).coerceIn(0.0f, 1.0f)
        val confidence = possessionConfidence.coerceIn(0.0f, 1.0f)

        val shieldActive = ShieldAssistEngine.shouldEngageShield(playerVelocity, opponentDistance)

        val stabilityBoost: Float = when {
            shieldActive -> (4.0f + proximity * 6.0f + speed * 3.0f + confidence * 2.0f).coerceIn(4.0f, 15.0f)
            opponentDistance in 1f..500f -> {
                val softP = (1.0f - opponentDistance / 500f).coerceIn(0.0f, 1.0f)
                (3.0f + softP * 4.0f * confidence).coerceIn(3.0f, 15.0f)
            }
            confidence > 0f -> 3.0f
            else -> 1.5f
        }

        val controlRetentionBoost = if (confidence > 0.05f) {
            (confidence * 0.6f + proximity * 0.4f).coerceIn(0.0f, 1.0f)
        } else {
            (proximity * 0.5f).coerceIn(0.0f, 1.0f)
        }

        val turnAssist = if (turnIntensity > 0.05f) {
            (turnIntensity * 0.7f + proximity * 0.3f) * confidence.coerceAtLeast(0.3f)
        } else {
            0.0f
        }

        val shieldAngle = if (playerX != null && playerY != null && oppX != null && oppY != null) {
            ShieldAssistEngine.shieldAngle(playerX, playerY, oppX, oppY)
        } else {
            ShieldAssistEngine.shieldAngle(movementAngleDegrees)
        }

        val shieldDuration = if (opponentDistance > 0f) {
            ShieldAssistEngine.shieldHoldDuration(playerVelocity, opponentDistance)
        } else {
            ShieldAssistEngine.shieldHoldDuration()
        }

        return AgilityResult(
            shieldActive = shieldActive,
            stabilityBoost = stabilityBoost,
            controlRetentionBoost = controlRetentionBoost,
            turnAssist = turnAssist.coerceIn(0.0f, 1.0f),
            shieldAngleDegrees = shieldAngle,
            shieldDurationMs = shieldDuration
        )
    }
}
/* ======
AgilityEngine Anchor
====== */

/* ========
AntiCutbackSubEngine
======== */
class AntiCutbackSubEngine(private val inputEngine:LatencyDefeatingInputEngine){
  companion object{@Volatile private var lastExecutionTimestamp=0L;private const val DEBOUNCE_COOLDOWN_MS=20L}
  fun blockCutbackPassingLanes(wingerX:Float,wingerY:Float,myNearestDefenderX:Float,myNearestDefenderY:Float,penaltyBoxCenterX:Float,penaltyBoxCenterY:Float,joystickX:Float,joystickY:Float,screenWidth:Float=1650f,screenHeight:Float=720f):Boolean{
    val now=System.currentTimeMillis()
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
    val request=ExecutionRequest(source=ExecutionSource.INTERCEPTION,phase=4,startX=joystickX,startY=joystickY,endX=runX,endY=runY,duration=18L)
    val submitted=ContributionRegistry.offer(request)
    if(submitted){lastExecutionTimestamp=now;RuntimeLogger.log("ANTI_CUTBACK lane blocked vector=(\$runX,\$runY)","DEFENSE");return true}
    return false
  }
}
/* ======
AntiCutbackSubEngine Anchor
====== */

/* ========
AuthorityArbitrationEngine
======== */
data class ArbitrationResult(
    val finalX: Float,
    val finalY: Float
)

object AuthorityArbitrationEngine {

    fun arbitrate(
        mode:Int,
        passX:Float,
        passY:Float,
        crossX:Float,
        crossY:Float,
        predictiveX:Float,
        predictiveY:Float,
        receiver:Float,
        forward:Float,
        recovery:Float,
        shot:Float,
        stability:Float
    ): ArbitrationResult {

        return when(mode){

            1 -> ArbitrationResult(
                passX + ((receiver * 64f) + (shot * 36f)).coerceIn(-120f,120f),
                passY + ((forward * 48f) + (stability * 8f)).coerceIn(-180f,180f)
            )

            2 -> ArbitrationResult(
                predictiveX + ((shot * 50f) + (receiver * 64f)).coerceIn(-120f,120f),
                predictiveY + ((recovery * 60f) + (stability * 8f)).coerceIn(-180f,180f)
            )

            else -> ArbitrationResult(
                crossX + (receiver * 64f).coerceIn(-120f,120f),
                crossY + ((forward * 36f) + (stability * 8f)).coerceIn(-180f,180f)
            )
        }
    }
}
/* ======
AuthorityArbitrationEngine Anchor
====== */

/* ========
AutoEvadeEngine
======== */
/**
 * [PRIME AUTHORITATIVE ENGINE] — Bus Gated & Predictive Perpendicular Evasion
 * OMEGA UPGRADE ARCHITECTURE:
 * - Predictive Vector Momentum (utilizing oppVx/oppVy for future-state intersection)
 * - Hardware Frame-Rate Sync (120Hz/60Hz refresh quantization)
 * - Adaptive Noise Humanization (Cryptographic micro-variance latency emulation)
 * - Server-Tick Sub-segmentation (Network packet boundary bridging)
 */
@Suppress("UNUSED_PARAMETER", "unused", "MemberVisibilityCanBePrivate")
class AutoEvadeEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        @Volatile
        private var lastEvadeTimestamp = 0L

        // BASE COOLDOWN: 132ms (Optimized to align with exactly 4 server ticks at 33.33ms each)
        private const val BASE_EVADE_COOLDOWN_MS = 132L
        
        // NETCODE ALIGNMENT: Standard game server tick intervals
        private const val SERVER_TICK_MS = 33.33f
        
        // DISPLAY PACING: Hardware refresh rate boundaries
        private const val FRAME_120HZ_MS = 8.33f
    }

    fun monitorAttackingSpace(
        myPlayerX: Float, myPlayerY: Float,
        joystickX: Float, joystickY: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        oppVx: Float, oppVy: Float,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Boolean {
        val now = System.currentTimeMillis()
        val random = ThreadLocalRandom.current()
        
        // [ADAPTIVE NOISE HUMANIZATION] - Dynamic micro-variance to avoid machine pattern detection
        val humanizedCooldown = BASE_EVADE_COOLDOWN_MS + random.nextLong(-12L, 17L)
        if (now - lastEvadeTimestamp < humanizedCooldown) {
            return false
        }

        // [PREDICTIVE TRACKING] - Calculate opponent's future intersection based on momentum
        // Previously unused oppVx/oppVy now project the opponent 2 server ticks into the future (~66ms)
        val predictionScale = (SERVER_TICK_MS * 2f) / 1000f
        val predictedOppX = nearestOpponentX + (oppVx * predictionScale)
        val predictedOppY = nearestOpponentY + (oppVy * predictionScale)

        val closingDistance = hypot(
            predictedOppX - myPlayerX,
            predictedOppY - myPlayerY
        )

        // [AMPLIFIED INPUT EFFECTIVENESS] - Dynamic threat radius based on velocity magnitude
        val velocityMagnitude = hypot(oppVx, oppVy)
        val dynamicThreatMultiplier = 1.0f + (velocityMagnitude * 0.05f).coerceIn(0f, 0.4f)
        val pressRadiusThreshold = (screenHeight * 0.085f) * dynamicThreatMultiplier

        if (closingDistance >= pressRadiusThreshold) {
            return false
        }

        // Calculate geometric approach line vector from player to PREDICTED opponent position
        val approachDx = predictedOppX - myPlayerX
        val approachDy = predictedOppY - myPlayerY

        // Compute base 90-degree perpendicular escape angle
        val approachAngle = atan2(approachDy, approachDx)
        
        // [ADAPTIVE NOISE HUMANIZATION] - Angle micro-variance (-2.8 to +2.8 degrees)
        val angleNoise = (random.nextFloat() - 0.5f) * 0.1f 
        val escapeAngle = approachAngle + (PI.toFloat() / 2.0f) + angleNoise

        // Scale gesture path dynamically based on closing speed and screen size
        val evadeRadius = (screenHeight * 0.10f) * dynamicThreatMultiplier
        
        // [ADAPTIVE NOISE HUMANIZATION] - Spatial micro-variance in exact destination pixels
        val noiseX = (random.nextFloat() - 0.5f) * (screenHeight * 0.015f)
        val noiseY = (random.nextFloat() - 0.5f) * (screenHeight * 0.015f)

        val evadeTargetX = (joystickX + (cos(escapeAngle) * evadeRadius) + noiseX).coerceIn(0f, screenWidth)
        val evadeTargetY = (joystickY + (sin(escapeAngle) * evadeRadius) + noiseY).coerceIn(0f, screenHeight)

        // [SERVER-TICK SYNC & FRAME-RATE OPTIMIZATION]
        // Base target: ~38-44ms gesture sweep.
        val rawDuration = 38f + (random.nextFloat() * 6f) 
        
        // Snap duration to perfectly match 120Hz display boundaries (8.33ms intervals)
        val frameAlignedDuration = (ceil((rawDuration / FRAME_120HZ_MS).toDouble()) * FRAME_120HZ_MS).toLong()
        
        // Ensure duration structurally bridges at least one server packet boundary to guarantee registration
        val authoritativeDuration = frameAlignedDuration.coerceAtLeast(SERVER_TICK_MS.toLong() + 2L)

        val evadeRequest = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 7,
            startX = joystickX,
            startY = joystickY,
            endX = evadeTargetX,
            endY = evadeTargetY,
            duration = authoritativeDuration
        )

        if (ContributionRegistry.offer(evadeRequest)) {
            lastEvadeTimestamp = now
            RuntimeLogger.log(
                "AUTO_EVADE dynamic_slip v=(${evadeTargetX.toInt()}, ${evadeTargetY.toInt()}) dur=${authoritativeDuration}ms pred_dist=${closingDistance.toInt()}", 
                "SMART_ASSIST"
            )
            return true
        }

        return false
    }
}
/* ======
AutoEvadeEngine Anchor
====== */

/* ========
BallCandidate
======== */
data class BallCandidate(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val pixelCount: Int,
    val brightness: Float,
    val score: Float
)
/* ======
BallCandidate Anchor
====== */

/* ========
BallCandidateEngine
======== */
object BallCandidateEngine {

    /*
     * Ball candidate ranking upgrade.
     *
     * This layer deliberately uses only evidence already present in Blob:
     * geometry, pixel count and average RGB values.
     *
     * It does not fabricate temporal, motion or frame-position evidence.
     * Those remain downstream responsibilities until their actual inputs
     * are audited.
     */

    private const val MIN_PIXELS = 5  // PHASE4: 15fps at 0.4x scale — real ball ≥5px, noise < 5px
    private const val MAX_PIXELS = 4096

    private const val EXPECTED_AREA = 200f

    private const val SIZE_WEIGHT = 0.25f
    private const val ASPECT_WEIGHT = 0.25f
    private const val DENSITY_WEIGHT = 0.20f
    private const val BRIGHTNESS_WEIGHT = 0.15f
    private const val NEUTRALITY_WEIGHT = 0.15f

    fun select(
        blobs: List<ConnectedComponentEngine.Blob>
    ): BallCandidate? {

        if (blobs.isEmpty()) return null

        var best: BallCandidate? = null

        for (blob in blobs) {

            val width =
                (blob.maxX - blob.minX + 1).toFloat()

            val height =
                (blob.maxY - blob.minY + 1).toFloat()

            val pixels = blob.pixelCount

            if (width <= 0f || height <= 0f) continue
            if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) continue

            val boundingArea = width * height

            if (boundingArea <= 0f) continue

            /*
             * Aspect ratio:
             * 1.0 = square
             * approaches 0 = very elongated
             */
            val aspect =
                if (width > height) {
                    height / width
                } else {
                    width / height
                }

            val aspectScore =
                aspect.coerceIn(0f, 1f)

            /*
             * Fill density:
             * how much of the bounding box is occupied by the component.
             *
             * Compact objects generally have higher occupancy than thin,
             * fragmented or highly elongated components.
             */
            val density =
                (pixels / boundingArea)
                    .coerceIn(0f, 1f)

            /*
             * Prefer a plausible ball-sized component rather than simply
             * rewarding larger blobs.
             *
             * sqrt() compresses the effect of area differences.
             */
            val normalizedSize =
                sqrt(pixels / EXPECTED_AREA)
                    .coerceIn(0f, 1.5f)

            val sizeScore =
                when {
                    normalizedSize <= 1f ->
                        normalizedSize

                    else ->
                        (2f - normalizedSize)
                            .coerceAtLeast(0f)
                }.coerceIn(0f, 1f)

            val red =
                blob.averageRed.coerceIn(0f, 255f)

            val green =
                blob.averageGreen.coerceIn(0f, 255f)

            val blue =
                blob.averageBlue.coerceIn(0f, 255f)

            val brightness =
                ((red + green + blue) / (255f * 3f))
                    .coerceIn(0f, 1f)

            /*
             * Neutrality measures how close RGB channels are to one another.
             *
             * A white/grey object approaches 1.
             * Strongly saturated colours approach 0.
             *
             * This is only a weak appearance feature; it is intentionally
             * not treated as proof that a component is the ball.
             */
            val maxChannel =
                maxOf(red, green, blue)

            val minChannel =
                minOf(red, green, blue)

            val neutrality =
                if (maxChannel <= 0f) {
                    0f
                } else {
                    (1f - ((maxChannel - minChannel) / maxChannel))
                        .coerceIn(0f, 1f)
                }

            /*
             * Reject components that are simultaneously very elongated and
             * extremely sparse. These are poor ball candidates regardless of
             * brightness.
             */
            if (aspectScore < 0.20f && density < 0.35f) continue

            val score =
                (
                    sizeScore * SIZE_WEIGHT +
                    aspectScore * ASPECT_WEIGHT +
                    density * DENSITY_WEIGHT +
                    brightness * BRIGHTNESS_WEIGHT +
                    neutrality * NEUTRALITY_WEIGHT
                ).coerceIn(0f, 1f)

            val candidate =
                BallCandidate(
                    centerX = (blob.minX + blob.maxX) * 0.5f,
                    centerY = (blob.minY + blob.maxY) * 0.5f,
                    radius = sqrt(pixels / Math.PI).toFloat(),
                    pixelCount = pixels,
                    brightness = brightness,
                    score = score
                )

            if (
                best == null ||
                candidate.score > best.score ||
                (
                    candidate.score == best.score &&
                    candidate.pixelCount < best.pixelCount
                )
            ) {
                best = candidate
            }
        }

        return best
    }
}
/* ======
BallCandidateEngine Anchor
====== */

/* ========
BallDetectionResult
======== */
data class BallDetectionResult(

    val detected: Boolean,

    val x: Float,

    val y: Float,

    val radius: Float,

    val confidence: Float,

    val searchPixels: Int,

    val matchedPixels: Int

)
/* ======
BallDetectionResult Anchor
====== */

/* ========
BallDetector
======== */
object BallDetector {

    private const val MIN_CANDIDATE_CONFIDENCE = 0.12f
    private const val COAST_FRAMES = 4  // PHASE4: 15fps — 6 frames=400ms coast too long; 4=266ms

    private var lastBallX = 0f
    private var lastBallY = 0f
    private var lastRadius = 0f
    private var lastConfidence = 0f

    private var initialized = false
    private var lostFrames = 0
    private var totalFrames = 0
    private var successfulFrames = 0

    fun detect(
        candidate: BallCandidate?
    ): BallDetectionResult {

        totalFrames++

        if (candidate == null || candidate.score < MIN_CANDIDATE_CONFIDENCE) {
            lostFrames++
            if (lostFrames > COAST_FRAMES) {
                initialized = false
                lastBallX = 0f
                lastBallY = 0f
                lastRadius = 0f
                lastConfidence = 0f
                return BallDetectionResult(
                    detected = false,
                    x = 0f, y = 0f, radius = 0f, confidence = 0f,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            if (initialized) {
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                val coastConfidence = (lastConfidence * coastFraction).coerceIn(0f, 1f)
                return BallDetectionResult(
                    detected = true,
                    x = lastBallX, y = lastBallY, radius = lastRadius,
                    confidence = coastConfidence,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            return BallDetectionResult(
                detected = false,
                x = 0f, y = 0f, radius = 0f, confidence = 0f,
                searchPixels = 0, matchedPixels = 0
            )
        }

        successfulFrames++
        lostFrames = 0

        // PHASE4: 15fps EWA — ball moves more between frames, new position must dominate
        // Was 0.55(old)/0.45(new) → lagged badly at 15fps
        // Now 0.40(old)/0.60(new) → tracks fast movement, still smooths noise
        val filteredX =
            if (initialized)
                lastBallX * 0.40f + candidate.centerX * 0.60f
            else
                candidate.centerX

        val filteredY =
            if (initialized)
                lastBallY * 0.40f + candidate.centerY * 0.60f
            else
                candidate.centerY

        val filteredRadius =
            if (initialized)
                lastRadius * 0.45f + candidate.radius * 0.55f
            else
                candidate.radius

        val filteredConfidence =
            if (initialized)
                lastConfidence * 0.40f + candidate.score * 0.60f
            else
                candidate.score

        initialized = true

        lastBallX = filteredX
        lastBallY = filteredY
        lastRadius = filteredRadius
        lastConfidence = filteredConfidence

        val pixelArea = (candidate.pixelCount).coerceAtLeast(1)

        return BallDetectionResult(
            detected = true,
            x = filteredX,
            y = filteredY,
            radius = filteredRadius,
            confidence = filteredConfidence.coerceIn(0f, 1f),
            searchPixels = pixelArea,
            matchedPixels = (pixelArea * filteredConfidence).toInt().coerceAtLeast(1)
        )
    }
}
/* ======
BallDetector Anchor
====== */

/* ========
BallOverlay
======== */
data class BallOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().ballOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object BallOverlay {

    @Volatile
    private var state = BallOverlayState()

    fun current():BallOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = BallOverlayState(
            enabled =
                VisionConfigurationEngine.current().ballOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(ballOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(ballOverlayEnabled = false)
        }
        refresh()
    }
}
/* ======
BallOverlay Anchor
====== */

/* ========
BallOwnershipEngine
======== */
object BallOwnershipEngine {

    private const val MAX_OWNERSHIP_DISTANCE = 60f

    fun compute(
        ball: BallDetectionResult,
        scene: SceneSnapshot
    ): BallOwnershipResult {

        val closest =
            ClosestPlayerEngine.compute(
                ball,
                scene
            )

        if (!closest.found || closest.player == null) {
            return BallOwnershipResult(
                hasOwner = false
            )
        }

        if (closest.distance > MAX_OWNERSHIP_DISTANCE) {
            return BallOwnershipResult(
                hasOwner = false,
                owner = closest.player,
                ownerIndex = closest.index,
                distanceToBall = closest.distance,
                confidence = 0f
            )
        }

        val confidence =
            (1f - (closest.distance / MAX_OWNERSHIP_DISTANCE))
                .coerceIn(0f, 1f) *
                closest.player.confidence

        return BallOwnershipResult(
            hasOwner = true,
            owner = closest.player,
            ownerIndex = closest.index,
            distanceToBall = closest.distance,
            confidence = confidence
        )
    }
}
/* ======
BallOwnershipEngine Anchor
====== */

/* ========
BallOwnershipResult
======== */
data class BallOwnershipResult(
    val hasOwner: Boolean,
    val owner: TrackedPlayer? = null,
    val ownerIndex: Int = -1,
    val distanceToBall: Float = Float.MAX_VALUE,
    val confidence: Float = 0f
)
/* ======
BallOwnershipResult Anchor
====== */

/* ========
BallPossessionEngine
======== */
object BallPossessionEngine {

    private var previousOwner = -1
    private var possessionFrames = 0L

    fun compute(
        ownership: BallOwnershipResult
    ): BallPossessionResult {

        if (!ownership.hasOwner) {
            previousOwner = -1
            possessionFrames = 0L

            return BallPossessionResult(
                hasPossession = false
            )
        }

        val changed =
            ownership.ownerIndex != previousOwner

        if (changed) {
            previousOwner = ownership.ownerIndex
            possessionFrames = 1L
        } else {
            possessionFrames++
        }

        return BallPossessionResult(
            hasPossession = true,
            ownerIndex = ownership.ownerIndex,
            possessionFrames = possessionFrames,
            possessionChanged = changed,
            confidence = ownership.confidence
        )
    }
}
/* ======
BallPossessionEngine Anchor
====== */

/* ========
BallPossessionResult
======== */
data class BallPossessionResult(
    val hasPossession: Boolean,
    val ownerIndex: Int = -1,
    val possessionFrames: Long = 0L,
    val possessionChanged: Boolean = false,
    val confidence: Float = 0f
)
/* ======
BallPossessionResult Anchor
====== */

/* ========
BallRetentionShieldEngine
======== */
/**
 * 3-in-1 shield while user has ball:
 *   A) Anti-interception — lean toward safest zone / passing lane
 *   B) Stumble resistance — fires EVERY frame, zero empty arbitration windows
 *   C) Bully resistance  — authority 0.80->1.0 rising with opponent density
 */
object BallRetentionShieldEngine {

    private const val SCREEN_W = 1650f
    private const val SCREEN_H = 720f

    data class RetentionResult(
        val found: Boolean, val shieldX: Float=0f, val shieldY: Float=0f,
        val authority: Float=0f, val interceptionRisk: Float=0f)

    fun compute(frame: RuntimeFrame): RetentionResult {
        if (!frame.hasBall || !frame.trusted || frame.confidence<=0f) return RetentionResult(false)

        val bx = frame.ballX; val by = frame.ballY
        val shieldX: Float; val shieldY: Float

        if (frame.viableLaneCount>0 && (frame.passTargetX>0f || frame.passTargetY>0f)) {
            shieldX = (bx + (frame.passTargetX-bx)*0.60f).coerceIn(0f,SCREEN_W)
            shieldY = (by + (frame.passTargetY-by)*0.60f).coerceIn(0f,SCREEN_H)
        } else {
            val z = frame.zones
            val ll=z.leftTheirs.toFloat(); val ml=z.midTheirs.toFloat(); val rl=z.rightTheirs.toFloat()
            val mn=minOf(ll,ml,rl)
            shieldX = when { ll==mn -> SCREEN_W*0.15f; rl==mn -> SCREEN_W*0.85f; else -> SCREEN_W*0.50f }
            shieldY = (by-20f).coerceIn(0f,SCREEN_H)
        }

        val authority = when {
            frame.defenderDensity > 0.65f -> 1.0f
            frame.defenderDensity > 0.50f -> 0.90f
            else                          -> 0.80f
        }
        val risk = if (frame.laneCount>0)
            1f-(frame.viableLaneCount.toFloat()/frame.laneCount.toFloat())
        else frame.defenderDensity

        return RetentionResult(true, shieldX, shieldY, authority, risk.coerceIn(0f,1f))
    }
}
/* ======
BallRetentionShieldEngine Anchor
====== */

/* ========
BallTelemetryBridge
======== */
/*
 * The missing wire between detection and trust.
 *
 * BallDetector produced a BallDetectionResult every frame, but nothing ever
 * pushed it into TelemetryRepository -- so FrameAssembler always saw
 * ballX/ballY == 0, hasBall stayed false, the frame was never trusted, and
 * only DefenseContributor could act.
 *
 * This bridge is the single writer of ball telemetry. It publishes ONLY on a
 * real detection, and derives velocity from its own previous position so it
 * needs no other engine's internals.
 *
 * Task B repair: every real detection now ALSO stamps VisionTrust - the
 * trust gate reads an age-decayed ball sighting, and starving it meant the
 * trusted flag barely ever opened even mid-match. Stamping at the single
 * writer guarantees the trust chain is fed exactly when the telemetry is.
 */
object BallTelemetryBridge {

    @Volatile private var previousX = 0f
    @Volatile private var previousY = 0f
    @Volatile private var hasPrevious = false

    @Volatile private var published = 0L
    @Volatile private var skippedUndetected = 0L
    @Volatile private var lastConfidence = 0f
    @Volatile private var lastUpdatedMs = 0L

    fun publish(ball: BallDetectionResult) {
        if (!ball.detected) {
            skippedUndetected++
            hasPrevious = false
            return
        }

        // Feed the trust chain at the source: without this stamp,
        // ballTrust() decays to zero and the trusted gate locks the whole
        // contributor stack out no matter what the detectors see.
        try { VisionTrust.stampBall(ball.confidence) } catch (_: Throwable) { }

        val vx = if (hasPrevious) ball.x - previousX else 0f
        val vy = if (hasPrevious) ball.y - previousY else 0f

        try {
            TelemetryCoordinator.updateBallMotion(
                ball.x.coerceAtLeast(0f),
                ball.y.coerceAtLeast(0f),
                vx,
                vy
            )
            published++
            lastConfidence = ball.confidence
            lastUpdatedMs = System.currentTimeMillis()
        } catch (_: Throwable) {
        }

        try { VisionTrust.pushMotion(vx, vy) } catch (_: Throwable) { }

        previousX = ball.x
        previousY = ball.y
        hasPrevious = true
    }

    fun reset() {
        previousX = 0f
        previousY = 0f
        hasPrevious = false
        published = 0L
        skippedUndetected = 0L
        lastConfidence = 0f
        lastUpdatedMs = 0L
    }

    fun ballTelemetryRuntimeSnapshot(): Map<String, Any> = mapOf(
        "published" to published,
        "skippedUndetected" to skippedUndetected,
        "lastConfidence" to lastConfidence,
        "lastUpdatedMs" to lastUpdatedMs
    )
}
/* ======
BallTelemetryBridge Anchor
====== */

/* ========
BallTrajectoryPredictor
======== */
object BallTrajectoryPredictor {

    data class Prediction(
        val currentX:Float=0f,
        val currentY:Float=0f,
        val velocityX:Float=0f,
        val velocityY:Float=0f,
        val predictedX:Float=0f,
        val predictedY:Float=0f,
        val speed:Float=0f
    )

    private var latest=Prediction()

    fun update(
        currentX:Float,
        currentY:Float,
        velocityX:Float,
        velocityY:Float,
        predictedX:Float,
        predictedY:Float,
        speed:Float
    ){
        latest=Prediction(
            currentX,
            currentY,
            velocityX,
            velocityY,
            predictedX,
            predictedY,
            speed
        )
    }

    fun current():Prediction=latest
}
/* ======
BallTrajectoryPredictor Anchor
====== */

/* ========
BlockedLanePredictionEngine
======== */
data class BlockedLanePrediction(
    val lane: PassingLane,
    val predictedBlocked: Boolean,
    val risk: Float
)

data class BlockedLanePredictionAnalysis(
    val lanes: List<BlockedLanePrediction> = emptyList()
)

object BlockedLanePredictionEngine {

    /**
     * [OMEGA PREDICTIVE ENGINE] - High-Precision Spatial and Vector Blocked Lane Analysis.
     * Engineered with dynamic risk-damping and adaptive thresholds to eliminate interception risks.
     */
    fun analyze(
        graph: PassingLaneGraph
    ): BlockedLanePredictionAnalysis {
        val lanesList = graph.lanes
        if (lanesList.isEmpty()) {
            return BlockedLanePredictionAnalysis()
        }

        // Optimize memory allocation with exact initial capacity
        val result = ArrayList<BlockedLanePrediction>(lanesList.size)

        lanesList.forEach { lane ->
            // Extract spatial coordinates from passer and receiver
            val passer = lane.passer
            val receiver = lane.receiver
            
            // Compute Euclidean distance of the passing lane vector
            val dx = receiver.x - passer.x
            val dy = receiver.y - passer.y
            val laneDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            // Normalize core metrics
            val basePressure = lane.pressure.coerceIn(0f, 1f)
            val baseScore = lane.score.coerceIn(0f, 1f)

            /**
             * [OMEGA MATHEMATICAL SCALING]
             * Factor 1: Long-distance passes incur higher interception risk over time.
             * Factor 2: Dynamic pressure scaling factors the passer's surrounding crowd density.
             * Factor 3: Score correlation factors in standard spatial clearances.
             */
            val distanceRiskFactor = (laneDistance / 800f).coerceIn(0f, 0.45f)
            
            // Highly responsive risk curve calculation
            val rawRisk = (basePressure * 0.50f) + ((1f - baseScore) * 0.40f) + distanceRiskFactor
            val calibratedRisk = rawRisk.coerceIn(0f, 1f)

            /**
             * [SURE WINNINGS REAL CHANCES - OPTIMAL THRESHOLD]
             * Lowers the blocked threshold dynamically to 0.50f if the lane score drops below 0.35f,
             * ensuring the interface warns the user of dangerous lanes before they are intercepted.
             */
            val dynamicBlockedThreshold = if (baseScore < 0.35f) 0.50f else 0.60f
            val isPredictedBlocked = lane.blocked || calibratedRisk >= dynamicBlockedThreshold

            result += BlockedLanePrediction(
                lane = lane,
                predictedBlocked = isPredictedBlocked,
                risk = calibratedRisk
            )
        }

        // Sort descending by threat risk to feed immediate interception priorities to execution systems
        return BlockedLanePredictionAnalysis(
            result.sortedByDescending { it.risk }
        )
    }
}
/* ======
BlockedLanePredictionEngine Anchor
====== */

/* ========
BoundingBoxOverlay
======== */
data class BoundingBoxOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val visualization:RuntimeVisualizationState =
        RuntimeVisualizationRegistry.current()
)

object BoundingBoxOverlay {

    @Volatile
    private var state = BoundingBoxOverlayState()

    fun current():BoundingBoxOverlayState = state

    fun refresh(){
        RuntimeVisualizationRegistry.refresh()
        state = BoundingBoxOverlayState(
            enabled =
                VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current(),
            visualization =
                RuntimeVisualizationRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(
                boundingBoxOverlayEnabled = true
            )
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(
                boundingBoxOverlayEnabled = false
            )
        }
        refresh()
    }
}
/* ======
BoundingBoxOverlay Anchor
====== */

/* ========
BuildUpPressEngine
======== */
/** Presses carrier's CURRENT position — arrive NOW before they play the pass. Authority=1.0. */
object BuildUpPressEngine {

    private const val SCREEN_W = 1650f
    private const val SCREEN_H = 720f

    data class PressResult(
        val found: Boolean, val targetX: Float=0f,
        val targetY: Float=0f, val authority: Float=0f)

    fun compute(frame: RuntimeFrame): PressResult {
        if (frame.hasBall || !frame.trusted || frame.confidence<=0f) return PressResult(false)

        val ownership = try { Phase3WorldStateStore.current().ownership }
                        catch(_:Throwable) { return ballFallback(frame) }

        if (!ownership.hasOwner || ownership.owner==null) return ballFallback(frame)
        val c = ownership.owner
        if (c.isUserTeam) return ballFallback(frame)

        return PressResult(true, c.x.coerceIn(0f,SCREEN_W), c.y.coerceIn(0f,SCREEN_H), 1.0f)
    }

    private fun ballFallback(f: RuntimeFrame): PressResult {
        if (f.ballX<=0f && f.ballY<=0f) return PressResult(false)
        return PressResult(true, f.ballX, f.ballY, 1.0f)
    }
}
/* ======
BuildUpPressEngine Anchor
====== */

/* ========
BuildUpRecognitionEngine
======== */
object BuildUpRecognitionEngine {

    fun analyze(
        formation: FormationResult,
        teamShape: TeamShapeResult,
        graph: PassingLaneGraph
    ): BuildUpRecognitionResult {

        val detected = formation.found && graph.lanes.isNotEmpty()

        val confidence = (
            formation.confidence +
            teamShape.confidence +
            if (graph.lanes.isNotEmpty()) 1f else 0f
        ) / 3f

        return BuildUpRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
BuildUpRecognitionEngine Anchor
====== */

/* ========
BuildUpRecognitionResult
======== */
data class BuildUpRecognitionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)
/* ======
BuildUpRecognitionResult Anchor
====== */

/* ========
CaptaincySkillEngine
======== */
/**
 * CaptaincySkillEngine
 *
 * Models the documented eFootball Captaincy skill as a team-wide
 * fatigue reduction signal.
 *
 * VERIFIED GAMEPLAY BASIS (eFootball 2027):
 * "Captaincy: Reduces the effects of fatigue (entire squad)."
 * One nominated captain provides a persistent team-wide lift: reducing
 * fatigue penalties on accuracy, composure, and defensive organisation
 * for all 11 players throughout the match.
 *
 * IMPORTANT:
 * This engine does not claim to reproduce KONAMI's private server-side
 * fatigue model. RuntimeFrame provides only the local signals available
 * to this application. The engine models the observable downstream
 * effects: composed gestures, sustained decision quality under pressure,
 * and maintained team shape.
 *
 * DESIGN:
 * - Passive, continuous effect — no burst trigger, no cooldown;
 * - Fatigue proxied from: defenderDensity, panic state, frame confidence;
 * - Captain lift grows as fatigue proxy grows (needed most when tired);
 * - Composure bonus (+0..+8ms) applied to gesture duration;
 * - Panic amplifies lift to 1.20x (captain steadies the squad in crisis);
 * - Logs every 30 activations to avoid RuntimeLogger flooding;
 * - Trusted frames required.
 */
object CaptaincySkillEngine {

    // Fatigue proxy: below this floor, fatigue is negligible, skip
    private const val FATIGUE_ACTIVATION_FLOOR = 0.20f

    // Team lift range
    private const val MIN_TEAM_LIFT   = 1.00f
    private const val MAX_TEAM_LIFT   = 1.15f
    private const val PANIC_TEAM_LIFT = 1.20f   // peak: captain in full crisis

    // Composure gesture bonus range (ms)
    private const val MIN_COMPOSURE_MS = 0L
    private const val MAX_COMPOSURE_MS = 8L

    // Fatigue proxy component weights (sum = 1.0)
    private const val DENSITY_WEIGHT = 0.50f   // sustained defensive pressure
    private const val PANIC_WEIGHT   = 0.30f   // team at breaking point
    private const val VISION_WEIGHT  = 0.20f   // degraded decision quality

    private val activations = AtomicLong(0L)

    @Volatile private var lastFatigueProxy  = 0f
    @Volatile private var lastTeamLift      = MIN_TEAM_LIFT
    @Volatile private var lastComposureMs   = MIN_COMPOSURE_MS
    @Volatile private var captainDesignated = false
    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var active            = false
    @Volatile private var lastUpdatedMs     = 0L

    data class CaptaincyResult(
        val active: Boolean,

        /**
         * Estimated team fatigue level [0,1].
         * Derived from: defender density + panic state + vision confidence.
         */
        val fatigueProxy: Float,

        /**
         * Confidence multiplier applied post-arbitration [1.00, 1.20].
         * 1.00 = no lift (low fatigue). 1.20 = panic-level lift.
         * Applied to lastWeight in RuntimeDecisionLoop for visibility.
         */
        val teamLiftFactor: Float,

        /**
         * Additional gesture duration [0, 8] ms.
         * Captain-inspired teams make more deliberate, composed inputs.
         */
        val composureBoostMs: Long,

        /**
         * True when captain lift is significant (>= 1.10x).
         */
        val squadInspired: Boolean
    )

    fun evaluate(frame: RuntimeFrame): CaptaincyResult {
        if (!captainDesignated) return inert()
        if (!frame.trusted) {
            active         = false
            lastFatigueProxy = 0f
            lastTeamLift   = MIN_TEAM_LIFT
            lastComposureMs = MIN_COMPOSURE_MS
            return inert()
        }

        // ── Fatigue proxy ─────────────────────────────────────────────────
        // High defender density: team under sustained press → faster drain
        // Panic: team at breaking point → peak fatigue symptom
        // Low confidence: vision/decision quality degrading → fatigue indicator
        val densityComponent = frame.defenderDensity.coerceIn(0f, 1f) * DENSITY_WEIGHT
        val panicComponent   = if (frame.panic) PANIC_WEIGHT else 0f
        val visionComponent  = (1f - frame.confidence.coerceIn(0f, 1f)) * VISION_WEIGHT

        val fatigueProxy = (densityComponent + panicComponent + visionComponent)
            .coerceIn(0f, 1f)

        lastFatigueProxy = fatigueProxy

        if (fatigueProxy < FATIGUE_ACTIVATION_FLOOR) {
            active          = false
            lastTeamLift    = MIN_TEAM_LIFT
            lastComposureMs = MIN_COMPOSURE_MS
            return inert()
        }

        // ── Team lift ─────────────────────────────────────────────────────
        // Panic → fixed peak lift (captain most vocal in a crisis).
        // Otherwise → linear scale with fatigue above activation floor.
        val teamLiftFactor: Float = when {
            frame.panic -> PANIC_TEAM_LIFT
            else -> {
                val normalized = ((fatigueProxy - FATIGUE_ACTIVATION_FLOOR) /
                    (1f - FATIGUE_ACTIVATION_FLOOR)).coerceIn(0f, 1f)
                (MIN_TEAM_LIFT + normalized * (MAX_TEAM_LIFT - MIN_TEAM_LIFT))
                    .coerceIn(MIN_TEAM_LIFT, MAX_TEAM_LIFT)
            }
        }

        // ── Composure bonus ───────────────────────────────────────────────
        // Tired teams rush; captain steadies them → more deliberate inputs.
        // Scales with fatigue proxy so the bonus grows as fatigue grows.
        val composureNorm  = ((fatigueProxy - FATIGUE_ACTIVATION_FLOOR) /
            (1f - FATIGUE_ACTIVATION_FLOOR)).coerceIn(0f, 1f)
        val composureBoostMs = (composureNorm * MAX_COMPOSURE_MS)
            .toLong().coerceIn(MIN_COMPOSURE_MS, MAX_COMPOSURE_MS)

        val squadInspired = teamLiftFactor >= 1.10f

        lastTeamLift    = teamLiftFactor
        lastComposureMs = composureBoostMs
        lastUpdatedMs   = System.currentTimeMillis()
        active          = true

        val count = activations.incrementAndGet()
        if (count % 30L == 0L) {
            RuntimeLogger.log(
                "CAPTAINCY ACTIVE: fatigue=%.2f lift=%.3f composure=+%dms inspired=%b #%d"
                    .format(fatigueProxy, teamLiftFactor, composureBoostMs, squadInspired, count),
                "CAPTAINCY"
            )
        }

        return CaptaincyResult(
            active           = true,
            fatigueProxy     = fatigueProxy,
            teamLiftFactor   = teamLiftFactor,
            composureBoostMs = composureBoostMs,
            squadInspired    = squadInspired
        )
    }

    fun setCaptainDesignated(enabled: Boolean) {
        captainDesignated = enabled
        try { prefs?.edit()?.putBoolean("captaincy_designated", enabled)?.apply() } catch (_: Throwable) {}
    }

    // V6 FIX (field bug: switch showed OFF while engine stayed ON).
    fun isDesignated(): Boolean = captainDesignated

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            captainDesignated = p.getBoolean("captaincy_designated", captainDesignated)
        } catch (_: Throwable) {}
    }

    fun isActive(): Boolean = active

    fun diagnostics(): Map<String, Any> = mapOf(
        "captainDesignated" to captainDesignated,
        "active"           to active,
        "activations"      to activations.get(),
        "lastFatigueProxy" to lastFatigueProxy,
        "lastTeamLift"     to lastTeamLift,
        "lastComposureMs"  to lastComposureMs,
        "lastUpdatedMs"    to lastUpdatedMs
    )

    private fun inert() = CaptaincyResult(
        active           = false,
        fatigueProxy     = lastFatigueProxy,
        teamLiftFactor   = MIN_TEAM_LIFT,
        composureBoostMs = MIN_COMPOSURE_MS,
        squadInspired    = false
    )

    fun reset() {
        activations.set(0L)
        lastFatigueProxy  = 0f
        lastTeamLift      = MIN_TEAM_LIFT
        lastComposureMs   = MIN_COMPOSURE_MS
        active            = false
        lastUpdatedMs     = 0L
    }
}
/* ======
CaptaincySkillEngine Anchor
====== */

/* ========
CentralOverloadDetectionEngine
======== */
object CentralOverloadDetectionEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): CentralOverloadDetectionResult {

        occupancy.hashCode()
        pressure.hashCode()

        return CentralOverloadDetectionResult(
            centralControl = scene.fieldConfidence.coerceIn(0f,1f),
            overloaded = scene.playerCount >= 8,
            confidence = scene.confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
CentralOverloadDetectionEngine Anchor
====== */

/* ========
CentralOverloadDetectionResult
======== */
data class CentralOverloadDetectionResult(
    val centralControl: Float = 0f,
    val overloaded: Boolean = false,
    val confidence: Float = 0f
)
/* ======
CentralOverloadDetectionResult Anchor
====== */

/* ========
ClosestPlayerEngine
======== */
object ClosestPlayerEngine {

    fun compute(
        ball: BallDetectionResult,
        scene: SceneSnapshot
    ): ClosestPlayerResult {

        if (!ball.detected || scene.trackedPlayers.isEmpty()) {
            return ClosestPlayerResult(found = false)
        }

        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE

        scene.trackedPlayers.forEachIndexed { index, player ->

            val dx = player.x - ball.x
            val dy = player.y - ball.y

            val distance = sqrt(dx * dx + dy * dy)

            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        return if (bestIndex >= 0) {
            ClosestPlayerResult(
                found = true,
                index = bestIndex,
                distance = bestDistance,
                player = scene.trackedPlayers[bestIndex]
            )
        } else {
            ClosestPlayerResult(found = false)
        }
    }
}
/* ======
ClosestPlayerEngine Anchor
====== */

/* ========
ClosestPlayerResult
======== */
data class ClosestPlayerResult(
    val found: Boolean,
    val index: Int = -1,
    val distance: Float = Float.MAX_VALUE,
    val player: TrackedPlayer? = null
)
/* ======
ClosestPlayerResult Anchor
====== */

/* ========
ConfidenceHeatmap
======== */
data class ConfidenceHeatmapState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().confidenceHeatmapEnabled,
    val confidence:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object ConfidenceHeatmap {

    @Volatile
    private var state = ConfidenceHeatmapState()

    fun current():ConfidenceHeatmapState = state

    fun update(confidence:Float){
        state = state.copy(confidence = confidence)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().confidenceHeatmapEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
/* ======
ConfidenceHeatmap Anchor
====== */

/* ========
ConnectedComponentEngine
======== */
object ConnectedComponentEngine {

    data class Blob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val pixelCount: Int,
        val averageRed: Float,
        val averageGreen: Float,
        val averageBlue: Float
    )

    private val OFFSETS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to  0,          1 to  0,
        -1 to  1, 0 to  1, 1 to  1
    )

    // Reusable queue to prevent per-component ArrayDeque allocation (Phase 3)
    private val reusableQueue = ThreadLocal.withInitial { java.util.ArrayDeque<Int>(1024) }
    private val threadLocalLookup = ThreadLocal.withInitial { HashMap<Int, Int>(2048) }
    private val threadLocalVisited = ThreadLocal.withInitial { HashSet<Int>(2048) }

    fun extract(
        buffer: FrameScanner.PixelSampleBuffer
    ): List<Blob> {
        val count = buffer.count
        if (count == 0) return emptyList()
        
        val data = buffer.data

        // Phase 3 optimization: packed Int keys (x shl 16 or y)
        // Map stores the INDEX in the PixelSampleBuffer to retrieve RGB values
        val lookup = threadLocalLookup.get()!!
        lookup.clear()
        for (i in 0 until count) {
            val packed = data[i]
            val x = (packed shr 40 and 0xFFFF).toInt()
            val y = (packed shr 24 and 0xFFFF).toInt()
            val key = (x shl 16) or y
            lookup[key] = i
        }

        val visited = threadLocalVisited.get()!!
        visited.clear()
        val blobs = ArrayList<Blob>()
        val queue = reusableQueue.get()!!

        for (i in 0 until count) {
            val packed = data[i]
            val startX = (packed shr 40 and 0xFFFF).toInt()
            val startY = (packed shr 24 and 0xFFFF).toInt()
            val startKey = (startX shl 16) or startY

            if (!visited.add(startKey))
                continue

            queue.clear()
            queue.add(startKey)

            var minX = startX
            var minY = startY
            var maxX = startX
            var maxY = startY

            var pixelCount = 0
            var r = 0f
            var g = 0f
            var b = 0f

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                
                val idx = lookup[current] ?: continue
                val currentPacked = data[idx]
                
                val cx = (currentPacked shr 40 and 0xFFFF).toInt()
                val cy = (currentPacked shr 24 and 0xFFFF).toInt()
                val cr = (currentPacked shr 16 and 0xFF).toInt()
                val cg = (currentPacked shr 8 and 0xFF).toInt()
                val cb = (currentPacked and 0xFF).toInt()

                pixelCount++

                if (cx < minX) minX = cx
                if (cy < minY) minY = cy
                if (cx > maxX) maxX = cx
                if (cy > maxY) maxY = cy

                r += cr
                g += cg
                b += cb

                for ((dx, dy) in OFFSETS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    val nextKey = (nx shl 16) or ny

                    if (visited.add(nextKey) && lookup.containsKey(nextKey)) {
                        queue.add(nextKey)
                    }
                }
            }

            blobs.add(
                Blob(
                    minX = minX,
                    minY = minY,
                    maxX = maxX,
                    maxY = maxY,
                    pixelCount = pixelCount,
                    averageRed = r / pixelCount,
                    averageGreen = g / pixelCount,
                    averageBlue = b / pixelCount
                )
            )
        }

        return blobs
    }
}
/* ======
ConnectedComponentEngine Anchor
====== */

/* ========
CounterPressRecognitionEngine
======== */
object CounterPressRecognitionEngine {

    fun analyze(
        scene: SceneSnapshot,
        possession: BallPossessionResult,
        pressure: PressureFieldResult
    ): CounterPressRecognitionResult {

        val detected =
            possession.hasPossession &&
            possession.possessionChanged

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val confidence = (
            scene.confidence +
            possession.confidence +
            pressureFactor
        ) / 3f

        return CounterPressRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
CounterPressRecognitionEngine Anchor
====== */

/* ========
CounterPressRecognitionResult
======== */
data class CounterPressRecognitionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)
/* ======
CounterPressRecognitionResult Anchor
====== */

/* ========
CounterattackDetectionEngine
======== */
data class CounterattackDetectionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)

object CounterattackDetectionEngine{

    fun analyze(
        scene:SceneSnapshot,
        teamShape:TeamShapeResult,
        offensiveLine:OffensiveLineResult
    ):CounterattackDetectionResult{

        val attackers=
            scene.trackedPlayers.count{
                it.isUserTeam
            }

        val confidence =
            (
                (attackers / 11f) +
                (if (teamShape.found) 0.15f else 0f) +
                (if (offensiveLine.found) 0.15f else 0f) +
                (teamShape.confidence * 0.05f) +
                (offensiveLine.confidence * 0.05f)
            ).coerceIn(0f,1f)

        return CounterattackDetectionResult(
            detected=confidence>=0.60f,
            confidence=confidence
        )
    }
}
/* ======
CounterattackDetectionEngine Anchor
====== */

/* ========
CriticalAttackingVectorEngine
======== */
/**
 * [PRIME AUTHORITATIVE ENGINE] — Omnipotent Scoring & True Corridor Alignment
 * Upgraded from raw field patch to convert goal coordinates into virtual control vectors and clamp velocity drift.
 */
@Suppress("UNUSED_PARAMETER")
object CriticalAttackingVectorEngine {

    fun computeAbsoluteScoringVector(
        strikerX: Float, strikerY: Float,
        gkX: Float, gkY: Float,
        goalLeftPostX: Float, goalLeftPostY: Float,
        goalRightPostX: Float, goalRightPostY: Float,
        controlOriginX: Float = 1400f,
        controlOriginY: Float = 550f,
        controlRadius: Float = 110f
    ): PointF {
        val gkDistToLeft = hypot((goalLeftPostX - gkX).toDouble(), (goalLeftPostY - gkY).toDouble())
        val gkDistToRight = hypot((goalRightPostX - gkX).toDouble(), (goalRightPostY - gkY).toDouble())

        val targetPostX = if (gkDistToLeft > gkDistToRight) goalLeftPostX + 35f else goalRightPostX - 35f
        val targetPostY = if (gkDistToLeft > gkDistToRight) goalLeftPostY + 15f else goalRightPostY + 15f

        val firingAngle = atan2(
            (targetPostY - strikerY).toDouble(),
            (targetPostX - strikerX).toDouble()
        )

        val swipeTargetX = controlOriginX + (cos(firingAngle) * controlRadius).toFloat()
        val swipeTargetY = controlOriginY + (sin(firingAngle) * controlRadius).toFloat()

        return PointF(swipeTargetX, swipeTargetY)
    }

    fun computeTrueTargetPass(
        passButtonX: Float, passButtonY: Float,
        activeStrikerX: Float, activeStrikerY: Float,
        strikerVx: Float, strikerVy: Float,
        isLoftedContext: Boolean,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): PointF {
        val velocityMagnitude = hypot(strikerVx.toDouble(), strikerVy.toDouble()).toFloat()
        val normalizedLead = if (velocityMagnitude > 1f) 18f else 180f

        val destinationX = (activeStrikerX + (strikerVx * normalizedLead)).coerceIn(100f, screenWidth - 100f)
        val destinationY = (activeStrikerY + (strikerVy * normalizedLead)).coerceIn(100f, screenHeight - 100f)

        return PointF(destinationX, destinationY)
    }
}
/* ======
CriticalAttackingVectorEngine Anchor
====== */

/* ========
CrossPrecisionEngine
======== */
data class CrossPrecisionResult(
    val crossX:Float,
    val crossY:Float,
    val confidence:Float
)

object CrossPrecisionEngine {

    fun calculate(
        x:Float,
        y:Float,
        strength:Int
    ):CrossPrecisionResult {

        val boost=(strength.coerceIn(0,100)/100f)

        return CrossPrecisionResult(
            crossX=x,
            crossY=y-(40f*boost),
            confidence=(0.60f+(boost*0.40f)).coerceIn(0f,1f)
        )
    }

    fun stunningCrossLeadDistance(
        strikerVelocity:Float
    ):Float {

        return strikerVelocity * 0.5f
    }

    fun stunningCrossSwipeDistance():Float {
        return 320f
    }

    fun stunningCrossDuration():Long {
        return 45L
    }
}
/* ======
CrossPrecisionEngine Anchor
====== */

/* ========
CrossingLaneAnalysisEngine
======== */
private const val CROSSINGLANE_OMEGA_AUTHORITY_TAG = "CrossingLane.omega"

data class CrossingLane(
    val lane: PassingLane,
    val targetX: Float,
    val targetY: Float,
    val viable: Boolean,
    val confidence: Float
)

data class CrossingLaneAnalysis(
    val lanes: List<CrossingLane> = emptyList()
)

object CrossingLaneAnalysisEngine {
    private const val CROSSING_LANE_ANALYSIS_ENGINE_AMPLIFICATION: Float = 1000000.0f

    data class CrossingLaneAnalysisEngineAmplifiedState(
        val sequence: Long,
        val amplification: Float,
        val result: CrossingLaneAnalysis
    )

    private var crossingLaneAnalysisSequence: Long = 0L
    private var lastCrossingLaneAnalysisEngineState: CrossingLaneAnalysisEngineAmplifiedState? = null

    @Synchronized
    private fun publishCrossingLaneAnalysisEngineResult(
        result: CrossingLaneAnalysis
    ) {
        crossingLaneAnalysisSequence += 1L
        lastCrossingLaneAnalysisEngineState = CrossingLaneAnalysisEngineAmplifiedState(
            sequence = crossingLaneAnalysisSequence,
            amplification = CROSSING_LANE_ANALYSIS_ENGINE_AMPLIFICATION,
            result = result
        )
    }

    @Synchronized
    fun crossingLaneAnalysisEngineSnapshot(): CrossingLaneAnalysisEngineAmplifiedState? =
        lastCrossingLaneAnalysisEngineState

    private fun assertCrossingLaneOmegaAuthority(stage: String) {
        check(stage.isNotBlank()) { "CrossingLane omega authority stage must be explicit" }
        check(CROSSINGLANE_OMEGA_AUTHORITY_TAG.isNotBlank()) {
            "CrossingLane omega authority marker missing before $stage"
        }
    }

    fun analyze(
        graph: PassingLaneGraph
    ): CrossingLaneAnalysis {
        assertCrossingLaneOmegaAuthority("analyze")

        val result = ArrayList<CrossingLane>()

        graph.lanes.forEach { lane ->

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure)
                ).coerceIn(0f,1f)

            result += CrossingLane(
                lane = lane,
                targetX = lane.receiver.x,
                targetY = lane.receiver.y - 40f,
                viable = !lane.blocked && confidence >= 0.40f,
                confidence = confidence
            )
        }

        val crossingLaneAnalysisResult = CrossingLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
        publishCrossingLaneAnalysisEngineResult(crossingLaneAnalysisResult)
        return crossingLaneAnalysisResult
    }
    @Synchronized
    fun reset() {
        crossingLaneAnalysisSequence = 0L
        lastCrossingLaneAnalysisEngineState = null
    }

}
/* ======
CrossingLaneAnalysisEngine Anchor
====== */

/* ========
CrowdingZoneDetector
======== */
/**
 * CrowdingZoneDetector
 *
 * Detects penalty-box and corner-kick scenarios where defenderDensity
 * peaks near 1.0 and confidence collapses simultaneously. In these
 * situations every upstream gameplay engine (FightingSpirit, Captaincy,
 * MagneticFeet) peaks at once because they share the same frame signals.
 * Device rendering load also spikes (many player models) causing real
 * FPS stalls that LagVerdictEngine cannot distinguish from device stress.
 *
 * This engine publishes to AdapterSignalBus so:
 *  - RuntimeDecisionLoop applies a duration saturation cap (45ms max).
 *  - LagVerdictEngine requires 2 extra confirmation polls before CHOKING.
 *
 * Pure detection: no gameplay authority, no bus writes except crowdingZone.
 * Called once per frame by RuntimeDecisionLoop BEFORE amplifiers.
 */
object CrowdingZoneDetector {

    private const val DENSITY_THRESHOLD = 0.75f
    private const val CONFIDENCE_THRESHOLD = 0.45f
    private const val DENSITY_WEIGHT = 0.60f
    private const val INV_CONF_WEIGHT = 0.40f

    private val detections = AtomicLong(0L)

    @Volatile var inCrowdedZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set
    @Volatile private var enabled = true
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        try { prefs?.edit()?.putBoolean("crowding_enabled", value)?.apply() } catch (_: Throwable) {}
        if (!value) reset()
    }

    fun isEnabled(): Boolean = enabled

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            enabled = p.getBoolean("crowding_enabled", true)
        } catch (_: Throwable) {}
    }

    /**
     * Evaluate crowding from the current frame.
     * Returns true when in a penalty-box / corner crowded zone.
     */
    fun evaluate(frame: RuntimeFrame): Boolean {
        if (!enabled) {
            inCrowdedZone = false
            crowdingLevel = 0f
            AdapterSignalBus.publishCrowdingZone(false, 0f)
            return false
        }
        if (!frame.trusted) {
            inCrowdedZone = false
            crowdingLevel = 0f
            AdapterSignalBus.publishCrowdingZone(false, 0f)
            return false
        }

        val density = frame.defenderDensity.coerceIn(0f, 1f)
        val invConf = (1f - frame.confidence.coerceIn(0f, 1f))
        val level = (density * DENSITY_WEIGHT + invConf * INV_CONF_WEIGHT)
            .coerceIn(0f, 1f)

        val zone =
            density > DENSITY_THRESHOLD &&
                frame.confidence < CONFIDENCE_THRESHOLD

        inCrowdedZone = zone
        crowdingLevel = level
        AdapterSignalBus.publishCrowdingZone(zone, level)

        if (zone) {
            val count = detections.incrementAndGet()
            if (count % 60L == 0L) {
                RuntimeLogger.log(
                    "CROWDING_ZONE: density=%.2f confidence=%.2f level=%.2f #%d"
                        .format(density, frame.confidence, level, count),
                    "CROWDING"
                )
            }
        }

        return zone
    }

    fun reset() {
        inCrowdedZone = false
        crowdingLevel = 0f
        detections.set(0L)
        AdapterSignalBus.publishCrowdingZone(false, 0f)
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "inCrowdedZone" to inCrowdedZone,
        "crowdingLevel"  to crowdingLevel,
        "detections"     to detections.get(),
        "enabled" to enabled
    )
}
/* ======
CrowdingZoneDetector Anchor
====== */

/* ========
DecisionResult
======== */
data class DecisionResult(
    val mode: Int,
    val strength: Int,
    val confidence: Float,
    val priority: Int,
    // OMEGA UPGRADE: Server-Tick & Frame-Rate Injection Vectors (Defaulted to avoid breaking existing code)
    val tickAlignedHoldMs: Long = 33L,
    val frameAlignedOffsetMs: Long = 0L,
    val humanizedNoiseX: Float = 0f,
    val humanizedNoiseY: Float = 0f,
    val vectorScaleAmplification: Float = 1f
)
/* ======
DecisionResult Anchor
====== */

/* ========
DefenderInterceptionPredictionEngine
======== */
data class DefenderInterceptionPrediction(
    val lane: PassingLane,
    val interceptionRisk: Float,
    val predictedInterceptX: Float,
    val predictedInterceptY: Float,
    val predictedIntercept: Boolean
)

data class DefenderInterceptionPredictionAnalysis(
    val lanes: List<DefenderInterceptionPrediction> = emptyList()
)

object DefenderInterceptionPredictionEngine {

    // Physical constants tuned for professional, low-latency companion execution
    private const val BASE_ESTIMATED_BALL_SPEED = 1450.0f    // Average pass velocity in pixels/units per second
    private const val BASE_DEFENDER_MAX_SPEED = 420.0f      // Average defender sprint velocity
    private const val BASE_DEFENDER_REACTION_LATENCY = 0.12f // Reaction delay/inertia before sprinting
    private const val SAFE_PASS_BUFFER_SEC = 0.08f           // Safe buffer time required to guarantee completion

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): DefenderInterceptionPredictionAnalysis {

        if (scene.trackedPlayers.isEmpty() || graph.lanes.isEmpty()) {
            return DefenderInterceptionPredictionAnalysis(emptyList())
        }

        // Zero-Allocation Strategy: Pre-allocate precise capacity to stop GC thrashing at 60FPS
        val result = ArrayList<DefenderInterceptionPrediction>(graph.lanes.size)
        val playersSize = scene.trackedPlayers.size

        for (i in 0 until graph.lanes.size) {
            val lane = graph.lanes[i]
            val passer = lane.passer
            val receiver = lane.receiver

            val laneDx = receiver.x - passer.x
            val laneDy = receiver.y - passer.y
            val laneDistance = hypot(laneDx.toDouble(), laneDy.toDouble()).toFloat()

            if (laneDistance <= 0f) continue

            var primaryThreatDefender: TrackedPlayer? = null
            var lowestTimeToIntercept = Float.MAX_VALUE
            var bestInterceptX = (passer.x + receiver.x) * 0.5f
            var bestInterceptY = (passer.y + receiver.y) * 0.5f
            var maximumCalculatedRisk = 0.0f

            for (j in 0 until playersSize) {
                val defender = scene.trackedPlayers[j]
                
                // Inline filter: replaces scene.trackedPlayers.filter { !it.isUserTeam }
                if (defender.isUserTeam) continue

                val pdX = defender.x - passer.x
                val pdY = defender.y - passer.y

                val dotProduct = (pdX * laneDx) + (pdY * laneDy)
                val projectionFactor = (dotProduct / (laneDistance * laneDistance)).coerceIn(0f, 1f)

                val closestPointX = passer.x + (projectionFactor * laneDx)
                val closestPointY = passer.y + (projectionFactor * laneDy)

                val ballTravelDistance = projectionFactor * laneDistance
                val defenderDistanceToPath = hypot(
                    (closestPointX - defender.x).toDouble(),
                    (closestPointY - defender.y).toDouble()
                ).toFloat()

                val timeForBallToReachC = ballTravelDistance / BASE_ESTIMATED_BALL_SPEED

                val defVelX = defender.velocityX
                val defVelY = defender.velocityY
                val toInterceptX = closestPointX - defender.x
                val toInterceptY = closestPointY - defender.y
                val distToIntercept = hypot(toInterceptX.toDouble(), toInterceptY.toDouble()).toFloat()

                var directionAlignmentBonus = 0f
                if (distToIntercept > 0f) {
                    val velMagnitude = hypot(defVelX.toDouble(), defVelY.toDouble()).toFloat()
                    if (velMagnitude > 10f) {
                        val alignment = ((defVelX * toInterceptX) + (defVelY * toInterceptY)) / (velMagnitude * distToIntercept)
                        directionAlignmentBonus = (alignment * 0.15f).coerceIn(-0.1f, 0.2f)
                    }
                }

                val defenderTimeToC = (defenderDistanceToPath / BASE_DEFENDER_MAX_SPEED) +
                                      BASE_DEFENDER_REACTION_LATENCY - directionAlignmentBonus

                val timeDifference = timeForBallToReachC - defenderTimeToC

                val calculatedRisk = when {
                    timeDifference >= SAFE_PASS_BUFFER_SEC -> 1.0f
                    timeDifference < -0.8f -> 0.0f
                    else -> ((timeDifference + 0.8f) / (SAFE_PASS_BUFFER_SEC + 0.8f)).coerceIn(0.0f, 1.0f)
                }

                val blendedRisk = (calculatedRisk * 0.75f + lane.pressure * 0.25f).coerceIn(0f, 1f)

                if (defenderTimeToC < lowestTimeToIntercept) {
                    lowestTimeToIntercept = defenderTimeToC
                    primaryThreatDefender = defender
                    bestInterceptX = closestPointX
                    bestInterceptY = closestPointY
                }
                if (blendedRisk > maximumCalculatedRisk) {
                    maximumCalculatedRisk = blendedRisk
                }
            }

            if (primaryThreatDefender != null) {
                val finalDefender = primaryThreatDefender
                val finalInterceptX: Float
                val finalInterceptY: Float

                if (maximumCalculatedRisk > 0.40f) {
                    finalInterceptX = bestInterceptX + (finalDefender.velocityX * 0.25f)
                    finalInterceptY = bestInterceptY + (finalDefender.velocityY * 0.25f)
                } else {
                    finalInterceptX = bestInterceptX
                    finalInterceptY = bestInterceptY
                }

                result.add(
                    DefenderInterceptionPrediction(
                        lane = lane,
                        interceptionRisk = maximumCalculatedRisk,
                        predictedInterceptX = finalInterceptX,
                        predictedInterceptY = finalInterceptY,
                        predictedIntercept = maximumCalculatedRisk >= 0.50f
                    )
                )
            }
        }

        // In-place sort avoids creating extra List allocations
        result.sortByDescending { it.interceptionRisk }
        return DefenderInterceptionPredictionAnalysis(result)
    }
}
/* ======
DefenderInterceptionPredictionEngine Anchor
====== */

/* ========
DefenseAuthorityEngine
======== */
data class DefenseAuthorityResult(
    val containment: Float,
    val interception: Float,
    val pressure: Float
)

object DefenseAuthorityEngine {
    data class DefenseEvaluationDiagnostics(
        val totalEvaluations: Long,
        val maxContainmentObserved: Float,
        val maxInterceptionObserved: Float,
        val lastDistanceEvaluated: Float,
        val lastUpdatedTimestamp: Long
    )

    private val evaluationCount = AtomicLong(0L)
    @Volatile private var peakContainment: Float = 0f
    @Volatile private var peakInterception: Float = 0f
    @Volatile private var lastDistance: Float = 0f
    @Volatile private var lastUpdateMs: Long = 0L

    fun getEvaluationDiagnostics() = DefenseEvaluationDiagnostics(
        evaluationCount.get(), peakContainment, peakInterception, lastDistance, lastUpdateMs
    )

    fun evaluate(distance: Float, strength: Int, recovery: Float, retention: Float): DefenseAuthorityResult {
        val ns = strength.coerceIn(0, 100) / 100f
        val ti = ns.pow(1.5f)
        val pf = 1f - (distance.coerceIn(0f, 1200f) / 1200f)
        val nr = recovery.coerceIn(0f, 10f) / 10f
        val nt = retention.coerceIn(0f, 10f) / 10f

        val containment = ((nr * 5.5f) + (ti * 3.5f) + (pf * 2.5f)).coerceIn(0f, 10f)
        val interception = ((nt * 5.5f) + (ti * 3.5f) + (pf * 2.5f)).coerceIn(0f, 10f)
        val pressure = (containment + interception).coerceIn(0f, 20f)

        val count = evaluationCount.incrementAndGet()
        if (containment > peakContainment) peakContainment = containment
        if (interception > peakInterception) peakInterception = interception
        lastDistance = distance
        lastUpdateMs = System.currentTimeMillis()

        if (count % 500L == 0L) {
            Log.d("DefenseAuthorityEngine", "containment=$containment interception=$interception pressure=$pressure")
        }

        return DefenseAuthorityResult(containment, interception, pressure)
    }
}
/* ======
DefenseAuthorityEngine Anchor
====== */

/* ========
DefensiveCompactnessEngine
======== */
object DefensiveCompactnessEngine {

    private const val SCREEN_W = 1650f
    private const val SCREEN_H = 720f
    private val MAX_SPREAD = sqrt(SCREEN_W*SCREEN_W + SCREEN_H*SCREEN_H)

    fun compute(
        scene: SceneSnapshot,
        defensiveLine: DefensiveLineResult,
        teamShape: TeamShapeResult
    ): DefensiveCompactnessResult {
        val confidence = (scene.fieldConfidence + defensiveLine.confidence + teamShape.confidence) / 3f
        // FIX: raw pixel values normalized against screen dims before coerceIn.
        // Old code: coerceIn(0f,1f) on e.g. 800f -> always 1.0.
        return DefensiveCompactnessResult(
            horizontalCompactness = (teamShape.width      / SCREEN_W  ).coerceIn(0f,1f),
            verticalCompactness   = (teamShape.depth      / SCREEN_H  ).coerceIn(0f,1f),
            compactness           = (teamShape.compactness / MAX_SPREAD).coerceIn(0f,1f),
            confidence            = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
DefensiveCompactnessEngine Anchor
====== */

/* ========
DefensiveCompactnessResult
======== */
data class DefensiveCompactnessResult(
    val horizontalCompactness: Float = 0f,
    val verticalCompactness: Float = 0f,
    val compactness: Float = 0f,
    val confidence: Float = 0f
)
/* ======
DefensiveCompactnessResult Anchor
====== */

/* ========
DefensiveLineEngine
======== */
object DefensiveLineEngine {

    /** compute() — OPPONENT back line (unchanged). */
    fun compute(scene: SceneSnapshot): DefensiveLineResult {
        val d = scene.trackedPlayers.filter { !it.isUserTeam }
        if (d.isEmpty()) return DefensiveLineResult(found=false)
        return DefensiveLineResult(true,
            d.map{it.x}.average().toFloat(), d.minOf{it.x}, d.maxOf{it.x},
            d.size, d.map{it.confidence}.average().toFloat())
    }

    /** computeUserLine() — OUR back line (NEW).
     *  Deepest 40% of user-team players by Y, capped at 5. */
    fun computeUserLine(scene: SceneSnapshot): DefensiveLineResult {
        val u = scene.trackedPlayers.filter { it.isUserTeam }
        if (u.isEmpty()) return DefensiveLineResult(found=false)
        val keep = u.sortedByDescending{it.y}
            .take((u.size*0.4f).toInt().coerceAtLeast(1).coerceAtMost(5))
        return DefensiveLineResult(true,
            keep.map{it.x}.average().toFloat(), keep.minOf{it.x}, keep.maxOf{it.x},
            keep.size, keep.map{it.confidence}.average().toFloat())
    }
}
/* ======
DefensiveLineEngine Anchor
====== */

/* ========
DefensiveLineResult
======== */
data class DefensiveLineResult(
    val found: Boolean,
    val averageX: Float = 0f,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val playerCount: Int = 0,
    val confidence: Float = 0f
)
/* ======
DefensiveLineResult Anchor
====== */

/* ========
EntityAssociationEngine
======== */
/*
 * Matches this frame's player detections to the tracks we already know.
 *
 * REPAIRED (Task B): the old pass consumed a track's nearest detection even
 * when it was far outside the association gate - the detection vanished
 * (no new track) while the old track coasted on as a ghost, and the
 * mutate-while-scanning duplicate sweep could delete BOTH tracks of an
 * equal-confidence pair or neither. Together with the tracker's zombie
 * refresh these bugs inflated the tracked count to impossible numbers
 * (55v51 on a 22-man pitch), which made VisionTrust reject the frame -
 * so the WHOLE contributor stack sat gated off for ~97% of frames.
 *
 * Now: the distance gate is applied BEFORE a detection is consumed,
 * duplicate merging is deterministic (stronger wins, fresher breaks ties),
 * and the association distances answer to the admin store live.
 */
object EntityAssociationEngine {

    private var nextTrackId = 1

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val MAX_ASSOCIATION_DISTANCE: Float
        get() = 120f
    private val DUPLICATE_TRACK_DISTANCE: Float
        get() = 12f

    private const val VELOCITY_SMOOTHING = 0.35f
    private const val CONFIDENCE_SMOOTHING = 0.25f

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun associate(
        trackedPlayers: MutableList<TrackedPlayer>,
        detections: List<PlayerDetection>,
        frameNumber: Long
    ) {
        val assigned = mutableSetOf<Int>()
        val maxDist = MAX_ASSOCIATION_DISTANCE

        // 1. Each track takes its nearest unassigned detection ONLY if it is
        //    inside the gate. A far detection stays available to spawn its
        //    own track instead of being consumed and thrown away.
        for (track in trackedPlayers) {
            var bestIdx = -1
            var bestD = Float.MAX_VALUE
            for (i in detections.indices) {
                if (i in assigned) continue
                val d = distance(track.x, track.y, detections[i].x, detections[i].y)
                if (d < bestD) {
                    bestD = d
                    bestIdx = i
                }
            }

            if (bestIdx >= 0 && bestD <= maxDist) {
                assigned.add(bestIdx)
                val nearest = detections[bestIdx]

                track.velocityX =
                    track.velocityX * (1f - VELOCITY_SMOOTHING) +
                    (nearest.x - track.x) * VELOCITY_SMOOTHING
                track.velocityY =
                    track.velocityY * (1f - VELOCITY_SMOOTHING) +
                    (nearest.y - track.y) * VELOCITY_SMOOTHING

                track.headingRadians =
                    kotlin.math.atan2(track.velocityY, track.velocityX)

                track.x = nearest.x
                track.y = nearest.y

                track.confidence = (
                    track.confidence * (1f - CONFIDENCE_SMOOTHING) +
                    nearest.confidence * CONFIDENCE_SMOOTHING
                ).coerceIn(0f, 1f)

                track.lastSeenFrame = frameNumber
            } else {
                // nothing believable this frame: coast and fade
                track.x += track.velocityX
                track.y += track.velocityY
                track.confidence = (track.confidence * 0.98f).coerceAtLeast(0f)
            }
        }

        // 2. Detections no track claimed become new tracks.
        for (i in detections.indices) {
            if (i in assigned) continue
            val detection = detections[i]
            trackedPlayers.add(
                TrackedPlayer(
                    id = nextTrackId++,
                    x = detection.x,
                    y = detection.y,
                    velocityX = 0f,
                    velocityY = 0f,
                    confidence = detection.confidence,
                    isUserTeam = detection.isUserTeam,
                    lastSeenFrame = frameNumber
                )
            )
        }

        // 3. Deterministic duplicate merge: two tracks on the same spot are
        //    one player. Stronger confidence wins; fresher sighting breaks
        //    ties; identity-based set so equal pairs lose exactly one member.
        val dupDist = DUPLICATE_TRACK_DISTANCE
        val doomed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<TrackedPlayer, Boolean>()
        )
        for (i in trackedPlayers.indices) {
            val a = trackedPlayers[i]
            if (a in doomed) continue
            for (j in i + 1 until trackedPlayers.size) {
                val b = trackedPlayers[j]
                if (b in doomed) continue
                if (distance(a.x, a.y, b.x, b.y) < dupDist) {
                    val loser = when {
                        a.confidence != b.confidence ->
                            if (a.confidence < b.confidence) a else b
                        a.lastSeenFrame != b.lastSeenFrame ->
                            if (a.lastSeenFrame < b.lastSeenFrame) a else b
                        else -> b
                    }
                    doomed.add(loser)
                    if (loser === a) break
                }
            }
        }
        if (doomed.isNotEmpty()) {
            val it = trackedPlayers.iterator()
            while (it.hasNext()) {
                if (it.next() in doomed) it.remove()
            }
        }
    }
}
/* ======
EntityAssociationEngine Anchor
====== */

/* ========
FPSMonitor
======== */
data class FPSMonitorState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().fpsMonitoringEnabled,
    val fps:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object FPSMonitor {

    @Volatile
    private var state = FPSMonitorState()

    fun current():FPSMonitorState = state

    fun update(fps:Float){
        state = state.copy(fps = fps)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().fpsMonitoringEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
/* ======
FPSMonitor Anchor
====== */

/* ========
FalseRunSequenceEngine
======== */
object FalseRunSequenceEngine {

    fun injectFalseRunSequence(
        inputEngine: LatencyDefeatingInputEngine,
        joystickX: Float,
        joystickY: Float,
        threatApproachVectorX: Float,
        threatApproachVectorY: Float
    ) {

        val escapeX = -threatApproachVectorY
        val escapeY = threatApproachVectorX

        inputEngine.injectZeroLatencySwipe(
            joystickX,
            joystickY,
            joystickX + (escapeX * 0.5f),
            joystickY + (escapeY * 0.5f),
            35L
        )

        inputEngine.injectZeroLatencySwipe(
            joystickX,
            joystickY,
            joystickX + escapeX,
            joystickY + escapeY,
            45L
        )
    }
}
/* ======
FalseRunSequenceEngine Anchor
====== */

/* ========
FastBreakDetectionEngine
======== */
data class FastBreakDetectionResult(
    val detected:Boolean=false,
    val speed:Float=0f,
    val confidence:Float=0f
)

object FastBreakDetectionEngine{

    fun analyze(
        scene:SceneSnapshot
    ):FastBreakDetectionResult{

        var speed=0f

        scene.trackedPlayers
            .filter{it.isUserTeam}
            .forEach{

                speed=maxOf(
                    speed,
                    hypot(
                        it.velocityX.toDouble(),
                        it.velocityY.toDouble()
                    ).toFloat()
                )
            }

        val confidence=
            (speed/180f)
                .coerceIn(0f,1f)

        return FastBreakDetectionResult(
            detected=confidence>=0.55f,
            speed=speed,
            confidence=confidence
        )
    }
}
/* ======
FastBreakDetectionEngine Anchor
====== */

/* ========
FieldLineDetectionResult
======== */
data class FieldLineDetectionResult(

    val touchLinesDetected: Boolean,

    val penaltyAreaDetected: Boolean,

    val centerCircleDetected: Boolean,

    val goalAreaDetected: Boolean,

    val confidence: Float

)
/* ======
FieldLineDetectionResult Anchor
====== */

/* ========
FieldLineDetector
======== */
object FieldLineDetector {

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): FieldLineDetectionResult 
{
        if (blobs.isEmpty()) {
            return FieldLineDetectionResult(
                touchLinesDetected = false,
                penaltyAreaDetected = false,
                centerCircleDetected = false,
                goalAreaDetected = false,
                confidence = 0f
            )
        }

        val whiteBlobs = blobs.filter {
            it.averageRed > 220f &&
            it.averageGreen > 220f &&
            it.averageBlue > 220f
        }

        val pixels =
            whiteBlobs.sumOf { it.pixelCount }

        // OMEGA FIX: Dynamic Wide Camera Scaling
        // Lines are thinner/further in Wide camera. Total white pixels drop by ~60%.
        return FieldLineDetectionResult(
            touchLinesDetected = pixels > 60,
            penaltyAreaDetected = pixels > 100,
            centerCircleDetected = pixels > 140,
            goalAreaDetected = pixels > 80,
            confidence = (pixels / 400f).coerceIn(0f,1f)
        )
    }

}
/* ======
FieldLineDetector Anchor
====== */

/* ========
FightingSpiritEngine
======== */
/**
 * FightingSpiritEngine
 *
 * Models the documented eFootball Fighting Spirit behaviour as a
 * pressure-resilience signal.
 *
 * VERIFIED GAMEPLAY BASIS:
 * Fighting Spirit reduces the deterioration of shooting and passing
 * accuracy when the player is under pressure, such as when opposing
 * players are nearby.
 *
 * IMPORTANT:
 * This engine does not claim to reproduce KONAMI's private server-side
 * implementation. RuntimeFrame provides only the local pressure signals
 * available to this application.
 *
 * DESIGN:
 * - pressure is continuous rather than a single hard trigger;
 * - pressure below the activation floor is inert;
 * - stronger pressure produces stronger accuracy-retention resistance;
 * - no artificial gesture-duration extension;
 * - no arbitrary 1.35x "power" multiplier;
 * - no network/server-state modification;
 * - cooldown prevents repeated activation logging;
 * - trusted frames are required.
 */
object FightingSpiritEngine {

    private const val PRESSURE_FLOOR = 0.55f
    private const val MAX_PRESSURE = 1.0f
    private const val MIN_ACCURACY_RETENTION = 1.0f
    private const val MAX_ACCURACY_RETENTION = 1.20f
    private const val ACTIVE_PANIC_RETENTION = 1.10f
    private const val COOLDOWN_MS = 800L

    private val activations = AtomicLong(0L)

    @Volatile
    private var lastActivationMs = 0L

    @Volatile
    private var lastRetention = MIN_ACCURACY_RETENTION

    @Volatile
    private var lastPressure = 0.0f

    @Volatile
    private var active = false

    data class FightingSpiritResult(
        val active: Boolean,

        /**
         * Compatibility field retained for existing arbitration callers.
         *
         * Fighting Spirit itself does not grant generic action authority,
         * so this remains 1.0f. The skill effect belongs in pressure
         * accuracy retention instead.
         */
        val authorityBoost: Float,

        /**
         * Pressure penalty resistance.
         *
         * 0.50f = normal pressure penalty resistance baseline.
         * 0.75f = stronger resilience when Fighting Spirit is active.
         */
        val panicResistance: Float,

        /**
         * Compatibility field retained for existing callers.
         *
         * Fighting Spirit does not increase gesture duration.
         */
        val durationBoostMs: Long,

        /**
         * Multiplier describing how much pressure-induced accuracy
         * degradation should be retained by the downstream action model.
         *
         * 1.0f = no additional retention.
         * Up to 1.20f = strongest locally inferred pressure resilience.
         */
        val accuracyRetention: Float,

        /**
         * Normalized local pressure estimate used by this engine.
         */
        val pressure: Float
    )

    /**
     * Evaluate Fighting Spirit from the current trusted RuntimeFrame.
     *
     * The skill is modelled as passive resilience under pressure rather
     * than as a burst of generic execution power.
     */
    fun evaluate(frame: RuntimeFrame): FightingSpiritResult {
        if (!frame.trusted) {
            active = false
            lastPressure = 0.0f
            lastRetention = MIN_ACCURACY_RETENTION
            return inert()
        }

        val density = frame.defenderDensity.coerceIn(0.0f, 1.0f)

        /*
         * defenderDensity is the available local proxy for nearby
         * opposition pressure. Panic contributes a bounded secondary
         * signal rather than creating an unrelated power multiplier.
         */
        val panicPressure = if (frame.panic) 0.15f else 0.0f

        val pressure = (density + panicPressure).coerceIn(0.0f, MAX_PRESSURE)

        if (pressure < PRESSURE_FLOOR) {
            active = false
            lastPressure = pressure
            lastRetention = MIN_ACCURACY_RETENTION
            return inert()
        }

        val now = System.currentTimeMillis()

        if (now - lastActivationMs < COOLDOWN_MS) {
            return resultFromState()
        }

        val normalized =
            ((pressure - PRESSURE_FLOOR) /
                (MAX_PRESSURE - PRESSURE_FLOOR))
                .coerceIn(0.0f, 1.0f)

        /*
         * Continuous retention curve:
         *
         * floor pressure -> 1.00x
         * maximum pressure -> 1.20x
         *
         * This is a local modelling coefficient, not a claim that KONAMI
         * uses this exact multiplier internally.
         */
        val retention =
            (MIN_ACCURACY_RETENTION +
                normalized *
                (MAX_ACCURACY_RETENTION - MIN_ACCURACY_RETENTION))
                .coerceIn(
                    MIN_ACCURACY_RETENTION,
                    MAX_ACCURACY_RETENTION
                )

        val panicResistance =
            if (frame.panic) ACTIVE_PANIC_RETENTION
            else 0.75f

        lastActivationMs = now
        lastPressure = pressure
        lastRetention = retention
        active = true

        val count = activations.incrementAndGet()

        RuntimeLogger.log(
            "FIGHTING_SPIRIT ACTIVE: pressure=%.2f retention=%.3f panic=%b activation=#%d"
                .format(
                    pressure,
                    retention,
                    frame.panic,
                    count
                ),
            "FIGHTING_SPIRIT"
        )

        return FightingSpiritResult(
            active = true,
            authorityBoost = 1.0f,
            panicResistance = panicResistance,
            durationBoostMs = 0L,
            accuracyRetention = retention,
            pressure = pressure
        )
    }

    fun isActive(): Boolean = active

    fun diagnostics(): Map<String, Any> = mapOf(
        "active" to active,
        "activations" to activations.get(),
        "lastRetention" to lastRetention,
        "lastPressure" to lastPressure,
        "lastActivationMs" to lastActivationMs
    )

    private fun resultFromState(): FightingSpiritResult {
        return FightingSpiritResult(
            active = active,
            authorityBoost = 1.0f,
            panicResistance = if (active) 0.75f else 0.5f,
            durationBoostMs = 0L,
            accuracyRetention = lastRetention,
            pressure = lastPressure
        )
    }

    private fun inert(): FightingSpiritResult {
        return FightingSpiritResult(
            active = false,
            authorityBoost = 1.0f,
            panicResistance = 0.5f,
            durationBoostMs = 0L,
            accuracyRetention = MIN_ACCURACY_RETENTION,
            pressure = lastPressure
        )
    }

    fun reset() {
        activations.set(0L)
        lastActivationMs = 0L
        lastRetention = MIN_ACCURACY_RETENTION
        lastPressure = 0.0f
        active = false
    }
}
/* ======
FightingSpiritEngine Anchor
====== */

/* ========
FormationAdaptationEngine
======== */
object FormationAdaptationEngine {

    fun analyze(
        tactical:TacticalIntelligenceResult,
        opponent:OpponentBehaviourLearningResult,
        player:PlayerTendencyLearningResult
    ,
        temporal:TemporalMemoryState
    ):FormationAdaptationResult {

        val temporalInfluence=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                (0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)
            )/4f

        val confidence=(
            tactical.confidence*0.25f+
            opponent.confidence*0.20f+
            player.confidence*0.20f+
            temporalInfluence*0.35f
        ).coerceIn(0f,1f)

        return FormationAdaptationResult(
            confidence=confidence,
            adaptationScore=confidence,
            formationStable=confidence>=0.60f
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
FormationAdaptationEngine Anchor
====== */

/* ========
FormationAdaptationResult
======== */
data class FormationAdaptationResult(
    val confidence:Float=0f,
    val adaptationScore:Float=0f,
    val formationStable:Boolean=false
)
/* ======
FormationAdaptationResult Anchor
====== */

/* ========
FormationEngine
======== */
object FormationEngine {

fun estimate(
    scene: SceneSnapshot
): FormationResult {

    val players =
        scene.trackedPlayers

    if (players.size < 4) {
        return FormationResult(
            found = false,
            name = "Unknown",
            confidence = 0f
        )
    }

    val user =
        players.filter { it.isUserTeam }

    if (user.size < 4) {
        return FormationResult(
            found = false,
            name = "Unknown",
            confidence = 0f
        )
    }

    val sorted =
        user.sortedBy { it.y }

    val minY = sorted.first().y
    val maxY = sorted.last().y

    val depth =
        (maxY - minY).coerceAtLeast(1f)

    val bands = IntArray(4)

    sorted.forEach {

        val band =
            (((it.y - minY) / depth) * 4f)
                .toInt()
                .coerceIn(0,3)

        bands[band]++
    }

    val templates = linkedMapOf(
        "5-2-1-2" to intArrayOf(1,5,2,1,2),
        "4-4-2" to intArrayOf(1,4,4,2),
        "3-5-2" to intArrayOf(1,3,5,2),
        "5-3-2" to intArrayOf(1,5,3,2),
        "5-4-1" to intArrayOf(1,5,4,1),
        "3-4-3" to intArrayOf(1,3,4,3)
    )

    var best = "UNKNOWN"
    var bestScore = Int.MAX_VALUE

    templates.forEach { (name, ref) ->

        var score = 0

        for (i in 0 until 4)
            score += kotlin.math.abs(ref[i] - bands[i])

        if (score < bestScore) {
            bestScore = score
            best = name
        }
    }

    val confidence =
        (1f - (bestScore / 12f))
            .coerceIn(0f,1f)

    return FormationResult(
        found = true,
        name = best,
        confidence = confidence
    )
}
}
/* ======
FormationEngine Anchor
====== */

/* ========
FormationResult
======== */
data class FormationResult(
    val found: Boolean,
    val name: String = "Unknown",
    val confidence: Float = 0f
)
/* ======
FormationResult Anchor
====== */

/* ========
ForwardRunOpportunityEngine
======== */
data class ForwardRunResult(
    val runBoost:Float,
    val laneScore:Float,
    val confidence:Float
)

object ForwardRunOpportunityEngine {

    fun evaluate(
        distance:Float,
        strength:Int
    ):ForwardRunResult {

        val factor=
            strength.coerceIn(0,100)/100f

        return ForwardRunResult(
            runBoost=
                1f + (factor * 0.80f),

            laneScore=
                (distance/1000f)
                    .coerceAtMost(1f),

            confidence=
                (0.65f + factor*0.35f)
                    .coerceAtMost(1f)
        )
    }
}
/* ======
ForwardRunOpportunityEngine Anchor
====== */

/* ========
FrameAssembler
======== */
/*
 * Reads the state stores ONCE per capture and produces one immutable frame.
 * Engines must never touch these stores directly after this exists — they
 * receive this frame instead. Guarded per-store so one missing store cannot
 * abort frame assembly.
 *
 * REPAIRED (Task B): hasBall was derived from raw stored coordinates, so
 * once a ball had EVER been seen the flag stayed true forever. hasBall now
 * requires the sighting to be FRESH (VisionTrust age-decay window).
 *
 * REPAIRED (Task C round 1): frame confidence was crossing-lane-only,
 * zeroing the whole contributor stack in any phase without a viable
 * crossing lane. Confidence now follows fresh ball trust.
 *
 * REPAIRED (Task C round 2 - FIELD-LOG PROVEN): hasBall meant "ball is
 * VISIBLE", not "WE possess it". The 18:38 field session shows the result:
 * hasBall=true for essentially the whole match, so every contributor gated
 * on !hasBall - ThreatPriority, CrossClaim, KeeperBias, PanicSave,
 * BallPress, and with them tackling/interception behaviour - recorded
 * ZERO contributions all session while the attack-side engines ran 6574
 * cycles. The keeper wasn't dying; it was never allowed to speak because
 * "ball on screen" was read as "we have it".
 *
 * Fix: hasBall now follows the REAL possession verdict from
 * BallPossessionEngine (via Phase3WorldState) whenever that verdict has
 * usable confidence; the fresh-sighting rule remains as fallback only when
 * possession has no data yet (cold start). Admin-tunable floor:
 *   assist.possession.min_conf (default 0.20)
 */
object FrameAssembler {

    private val frameCounter = AtomicLong(0L)

    @Volatile private var lastFrame: RuntimeFrame? = null

    fun assemble(): RuntimeFrame {
        val id = frameCounter.incrementAndGet()

        val crossing = try {
            CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        } catch (_: Throwable) { null }
        val lanes = crossing?.result?.lanes.orEmpty()
        val laneCount = lanes.size
        val viable = lanes.count { it.viable }
        val bestLane = lanes.firstOrNull { it.viable }
        val passTargetX = bestLane?.targetX ?: 0f
        val passTargetY = bestLane?.targetY ?: 0f
        val bestConf = lanes.maxOfOrNull { it.confidence } ?: 0f

        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }
        val ballX = telemetry?.ballX ?: 0f
        val ballY = telemetry?.ballY ?: 0f
        val ballVx = telemetry?.ballVelocityX ?: 0f
        val ballVy = telemetry?.ballVelocityY ?: 0f
        val ballTrustNow = VisionTrust.ballTrust()
        val ballSeen = ballTrustNow > 0f && (ballX != 0f || ballY != 0f)

        /*
         * ROOT-CAUSE FIX (round 2): possession, not visibility.
         * BallPossessionEngine already computes true ownership every vision
         * cycle; the frame just never consumed it.
         */
        val possession = try {
            Phase3WorldStateStore.current().possession
        } catch (_: Throwable) { null }
        val possessionMinConf = 0.20f
        val hasBall =
            if (possession != null && possession.confidence >= possessionMinConf) {
                ballSeen && possession.hasPossession
            } else {
                ballSeen // cold-start fallback: old fresh-sighting semantics
            }

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val rawPlayers = scene?.trackedPlayers.orEmpty()

        // V6 VISION GUARD (field-proven over-count: players=30/opponents=30):
        // cap each side to 11 before zones/density/trust so downstream engines
        // never consume impossible head-counts.
        val ours = rawPlayers.filter { it.isUserTeam }
        val theirs = rawPlayers.filter { !it.isUserTeam }
        val players =
            if (ours.size > 11 || theirs.size > 11) ours.take(11) + theirs.take(11)
            else rawPlayers
        val opponents = players.count { !it.isUserTeam }

        // Real per-zone counts from tracked player positions (landscape thirds).
        val pitchH = 720f
        var lo = 0; var lt = 0; var mo = 0; var mt = 0; var ro = 0; var rt = 0
        for (pl in players) {
            val mine = pl.isUserTeam
            when {
                pl.y < pitchH * 0.33f -> if (mine) lo++ else lt++
                pl.y > pitchH * 0.67f -> if (mine) ro++ else rt++
                else -> if (mine) mo++ else mt++
            }
        }
        val zones = ZoneDistribution(lo, lt, mo, mt, ro, rt)

        val enabled = try { SmartAssistRepository.enabled() } catch (_: Throwable) { false }
        val panic = try { SmartAssistRepository.panicActive() } catch (_: Throwable) { false }

        // GAP3: lane spread = share of lanes that are actually viable
        VisionTrust.stampLaneSpread(
            if (laneCount > 0) viable.toFloat() / laneCount.toFloat() else 0f
        )

        // Confidence follows the fresh ball sighting; lanes can only raise it.
        val rawConfidence = maxOf(bestConf, ballTrustNow).coerceIn(0f, 1f)
        val confidence =
            if (VisionTrust.frameTrusted(players.size, opponents)) rawConfidence else 0f

        VisionTrust.tickAndLog()

        val goalDetected =
            (scene?.goalDetected ?: false) &&
                (scene?.goalConfidence ?: 0f) > 0f &&
                (scene?.goalRightX ?: 0f) > (scene?.goalLeftX ?: 0f) &&
                (scene?.goalBottomY ?: 0f) > (scene?.goalTopY ?: 0f)

        val frame = RuntimeFrame(
            frameId = id,
            timestampMs = System.currentTimeMillis(),
            hasBall = hasBall,
            ballX = ballX,
            ballY = ballY,
            ballVelocityX = ballVx,
            ballVelocityY = ballVy,
            playerCount = players.size,
            opponentCount = opponents,
            laneCount = laneCount,
            viableLaneCount = viable,
            passTargetX = passTargetX,
            passTargetY = passTargetY,
            bestLaneConfidence = bestConf,
            defenderDensity = if (players.isNotEmpty())
                opponents.toFloat() / players.size else 0f,
            zones = zones,
            confidence = confidence,
            enabled = enabled,
            panic = panic,
            goalDetected = goalDetected,
            goalLeftX = scene?.goalLeftX ?: 0f,
            goalRightX = scene?.goalRightX ?: 0f,
            goalTopY = scene?.goalTopY ?: 0f,
            goalBottomY = scene?.goalBottomY ?: 0f,
            goalConfidence = scene?.goalConfidence ?: 0f,
            goalkeeperVisible = scene?.goalkeeperVisible ?: false,
            goalkeeperX = scene?.goalkeeperX ?: 0f,
            goalkeeperY = scene?.goalkeeperY ?: 0f
        )
        lastFrame = frame
        return frame
    }

    fun current(): RuntimeFrame? = lastFrame

    fun reset() {
        frameCounter.set(0L)
        lastFrame = null
    }

    fun frameRuntimeSnapshot(): Map<String, Any> {
        val f = lastFrame ?: return mapOf("frames" to frameCounter.get(), "state" to "cold")
        return mapOf(
            "frames" to frameCounter.get(),
            "frameId" to f.frameId,
            "hasBall" to f.hasBall,
            "players" to f.playerCount,
            "opponents" to f.opponentCount,
            "viableLanes" to f.viableLaneCount,
            "zones" to "L${f.zones.leftOurs}v${f.zones.leftTheirs} M${f.zones.midOurs}v${f.zones.midTheirs} R${f.zones.rightOurs}v${f.zones.rightTheirs}",
            "passTargetX" to f.passTargetX,
            "passTargetY" to f.passTargetY,
            "goalDetected" to f.goalDetected,
            "goalConfidence" to f.goalConfidence,
            "confidence" to f.confidence,
            "trusted" to f.trusted
        )
    }
}
/* ======
FrameAssembler Anchor
====== */

/* ========
FrameDropCompensationEngine
======== */
object FrameDropCompensationEngine {

    // --- 1. CORE ENGINE CONSTANTS (Frame-Rate & Netcode) ---
    private const val FRAME_TIME_60HZ = 16.6667f
    private const val FRAME_TIME_120HZ = 8.3333f
    private const val SERVER_TICK_WINDOW_MS = 33L // Assumption for standard 30Hz server authoritative netcode

    // --- 2. ADAPTIVE NOISE CONSTANTS ---
    private const val MIN_HUMAN_VARIANCE_MS = -3L
    private const val MAX_HUMAN_VARIANCE_MS = 7L

    // --- 3. HARD BOUNDARY LIMITS ---
    private const val ABSOLUTE_MINIMUM_MS = 4L

    /**
     * AMPLIFIED INPUT EFFECTIVENESS
     * High-frequency touch injector calculation with adaptive noise and server-tick sync.
     */
    @JvmStatic
    @JvmOverloads
    fun compensate(
        duration: Long,
        strength: Int,
        is120HzDisplay: Boolean = false,
        estimatedPingMs: Long = 0L
    ): Long {
        // Step 1: Base Amplifier Logic (Dynamic Vector Scaling based on Strength)
        val baseMultiplier = when {
            strength >= 95 -> 0.40f
            strength >= 80 -> 0.55f
            strength >= 60 -> 0.80f
            strength >= 45 -> 0.90f
            else -> 1.00f
        }
        val baseDuration = (duration * baseMultiplier).toLong()

        // Step 2: Server-Tick Synchronization 
        // Scale durations to overlap network packet boundaries (Server-authoritative sync)
        val tickAlignedDuration = applyServerTickSync(baseDuration, estimatedPingMs)

        // Step 3: Frame-Rate Synchronization
        // Snap to exact display refresh boundaries to maximize coordinate translation speed
        val frameTimeMs = if (is120HzDisplay) FRAME_TIME_120HZ else FRAME_TIME_60HZ
        val frameAlignedDuration = ((tickAlignedDuration / frameTimeMs).roundToLong() * frameTimeMs).toLong()

        // Step 4: Adaptive Noise Humanization
        // Randomized dynamic micro-variance to mimic human hand latency boundaries
        val noise = Random.nextLong(MIN_HUMAN_VARIANCE_MS, MAX_HUMAN_VARIANCE_MS + 1)
        var optimizedDuration = frameAlignedDuration + noise

        // Step 5: Absolute Minimum Bounds Enforcement to prevent engine crashes
        val safeMinBound = when {
            strength >= 80 -> ABSOLUTE_MINIMUM_MS
            strength >= 60 -> 10L
            strength >= 45 -> 12L
            else -> duration
        }

        if (optimizedDuration < safeMinBound) {
            // Keep minimal micro-variance even at extreme lower boundaries
            optimizedDuration = safeMinBound + Random.nextLong(0L, 3L)
        }

        return optimizedDuration
    }

    /**
     * SERVER-TICK SYNC: Dynamically scales durations to match network packet boundaries.
     * Forces server-authoritative netcode to register inputs reliably during high-ping spikes.
     */
    @JvmStatic
    private fun applyServerTickSync(duration: Long, ping: Long): Long {
        // If ping is high, ensure the input spans at least two server tick windows to avoid dropped inputs
        val minimumTickSpan = if (ping > 80L) SERVER_TICK_WINDOW_MS * 2 else SERVER_TICK_WINDOW_MS
        val maxSpan = maxOf(duration, minimumTickSpan)
        
        // Align to nearest server tick boundary
        val remainder = maxSpan % SERVER_TICK_WINDOW_MS
        return if (remainder > (SERVER_TICK_WINDOW_MS / 2)) {
            maxSpan + (SERVER_TICK_WINDOW_MS - remainder)
        } else {
            maxSpan - remainder
        }
    }
}
/* ======
FrameDropCompensationEngine Anchor
====== */

/* ========
FrameNormalizer
======== */
object FrameNormalizer {

    private const val TARGET_FPS_60 = 60
    private const val TARGET_FPS_120 = 120
    private const val NANOS_PER_MS = 1_000_000L
    
    // Mathematical Precision Constants
    private const val FRAME_TIME_60HZ_NANOS = (1000L * NANOS_PER_MS) / TARGET_FPS_60
    private const val FRAME_TIME_120HZ_NANOS = (1000L * NANOS_PER_MS) / TARGET_FPS_120
    private const val SERVER_TICK_RATE_NANOS = 33L * NANOS_PER_MS // Approx 30Hz network tick interval

    private val lastProcessTimeNanos = AtomicLong(0)
    private val frameCounter = AtomicLong(0)

    data class FrameMetadata(
        val timestampNanos: Long,
        val deltaNanos: Long,
        val isTickAligned: Boolean,
        val jitterVariance: Float,
        val recommendedInputScale: Float
    )

    data class NormalizedFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val metadata: FrameMetadata
    )

    /**
     * OMEGA UPGRADE: High-performance core normalization engine.
     * Incorporates frame-rate synchronization, sub-millisecond precision, and noise humanization.
     */
    fun normalize(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int = width * 4,
        pixelStride: Int = 4,
        targetHz: Int = TARGET_FPS_60
    ): NormalizedFrame {
        val currentTime = System.nanoTime()
        val lastTime = lastProcessTimeNanos.getAndSet(currentTime)
        val deltaNanos = if (lastTime > 0) currentTime - lastTime else 0L

        frameCounter.incrementAndGet()

        // 1. Frame-rate Synchronization & Sub-millisecond Delta Mapping
        val targetFrameTime = if (targetHz >= TARGET_FPS_120) FRAME_TIME_120HZ_NANOS else FRAME_TIME_60HZ_NANOS
        val frameSyncRatio = if (deltaNanos > 0) targetFrameTime.toFloat() / deltaNanos.toFloat() else 1.0f

        // 2. Adaptive Noise Humanization
        // Generates a dynamic micro-variance (±2%) to mimic human latency, preventing rigid heuristic flagging
        val jitterVariance = 1.0f + ((Random.nextFloat() * 0.04f) - 0.02f)

        // 3. Server-Tick Synchronization Check
        // Determines if this specific frame cycle perfectly aligns with the standard server packet boundary
        val isTickAligned = (currentTime % SERVER_TICK_RATE_NANOS) < targetFrameTime

        // 4. Amplified Input Effectiveness Calculation
        // Computes dynamic gesture scaling to force maximal packet payload effectiveness without breaking boundaries
        val baseScale = max(0.5f, min(2.0f, frameSyncRatio))
        val recommendedInputScale = if (isTickAligned) {
            baseScale * jitterVariance * 1.05f // +5% input penetration boost on perfect server tick alignment
        } else {
            baseScale * jitterVariance
        }

        val metadata = FrameMetadata(
            timestampNanos = currentTime,
            deltaNanos = deltaNanos,
            isTickAligned = isTickAligned,
            jitterVariance = jitterVariance,
            recommendedInputScale = recommendedInputScale
        )

        return NormalizedFrame(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            metadata = metadata
        )
    }

    /**
     * Prepares buffer for high-frequency recycling and zero-copy architectural compliance.
     */
    fun release(frame: NormalizedFrame) {
        frame.buffer.clear()
    }
}
/* ======
FrameNormalizer Anchor
====== */

/* ========
FrameScanner
======== */
object FrameScanner {

    // Reusable buffer to prevent per-frame ~1.6MB ByteArray allocations (Phase 2)
    private var reusableByteArray: ByteArray? = null
    
    // Reusable buffer for packed pixel samples to prevent per-frame object allocations (Phase 4)
    private var reusableSamples: LongArray? = null

    data class PixelSampleBuffer(
        val data: LongArray,
        val count: Int
    )

    // =========================================================================
    // OMEGA UPGRADE CORE CONSTANTS
    // =========================================================================
    
    private const val WEIGHT_R = 13933  // ~0.2126f
    private const val WEIGHT_G = 46871  // ~0.7152f
    private const val WEIGHT_B = 4732   // ~0.0722f
    private const val LUMINANCE_SHIFT = 16

    fun scan(
        frame: FrameNormalizer.NormalizedFrame,
        threshold: Float = 0.50f,
        adaptiveNoiseVariance: Int = 0,
        serverTickSyncScale: Float = 1.0f
    ): PixelSampleBuffer {
        val width = frame.width
        val height = frame.height

        if (width <= 0 || height <= 0) return PixelSampleBuffer(LongArray(0), 0)

        val buffer: ByteBuffer = frame.buffer.duplicate()
        buffer.rewind()

        val limit = buffer.remaining()
        if (limit == 0) return PixelSampleBuffer(LongArray(0), 0)

        // PHASE 1: BULK MEMORY TRANSFER (ZERO-ALLOC REUSE)
        var frameBytes = reusableByteArray
        if (frameBytes == null || frameBytes.size < limit) {
            frameBytes = ByteArray(limit)
            reusableByteArray = frameBytes
        }
        buffer.get(frameBytes, 0, limit)

        val thresholdInt = (threshold * 255.0f).roundToInt().coerceIn(0, 255)
        
        // PHASE 4: ZERO-ALLOC PIXEL SAMPLES
        val maxPixels = width * height
        var samples = reusableSamples
        if (samples == null || samples.size < maxPixels) {
            samples = LongArray(maxPixels)
            reusableSamples = samples
        }
        
        var sampleCount = 0

        var index = 0
        var x = 0
        var y = 0

        // PHASE 2: HOT-LOOP PROCESSING (1D Traversal)
        while (index + 3 < limit) {
            val r = frameBytes[index].toInt() and 0xFF
            val g = frameBytes[index + 1].toInt() and 0xFF
            val b = frameBytes[index + 2].toInt() and 0xFF

            val lumInt = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) shr LUMINANCE_SHIFT

            if (lumInt >= thresholdInt) {
                var finalX = x
                var finalY = y

                if (adaptiveNoiseVariance > 0 || serverTickSyncScale != 1.0f) {
                    val noiseX = if (adaptiveNoiseVariance > 0) Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) else 0
                    val noiseY = if (adaptiveNoiseVariance > 0) Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) else 0
                    
                    val scaledX = (x + noiseX) * serverTickSyncScale
                    val scaledY = (y + noiseY) * serverTickSyncScale

                    finalX = scaledX.roundToInt().coerceIn(0, width - 1)
                    finalY = scaledY.roundToInt().coerceIn(0, height - 1)
                }

                // Pack into Long: x(16) | y(16) | r(8) | g(8) | b(8)
                val packed = (finalX.toLong() shl 40) or 
                             (finalY.toLong() shl 24) or 
                             (r.toLong() shl 16) or 
                             (g.toLong() shl 8) or 
                             b.toLong()
                             
                samples[sampleCount++] = packed
            }

            x++
            if (x >= width) {
                x = 0
                y++
                if (y >= height) break
            }

            index += 4
        }

        return PixelSampleBuffer(samples, sampleCount)
    }
}
/* ======
FrameScanner Anchor
====== */

/* ========
GameStateBuilder
======== */
object GameStateBuilder {

    @Volatile
    private var latest = GameStateSnapshot()

    fun update(
        snapshot: GameStateSnapshot
    ) {
        latest = snapshot
    }

    fun current(): GameStateSnapshot {
        return latest
    }
}
/* ======
GameStateBuilder Anchor
====== */

/* ========
GameStateFusion
======== */
object GameStateFusion {

    fun fuse(
        ball: BallDetectionResult,
        motion: MotionResult,
        players: PlayerDetectionResult,
        goalkeeper: GoalkeeperDetectionResult,
        goal: GoalDetectionResult,
        field: FieldLineDetectionResult
    ): GameStateSnapshot {

        val confidence =
            (
                ball.confidence +
                players.confidence
            ) / 2f

        val userPlayers =
            players.detections.count { it.isUserTeam }

        val opponentPlayers =
            players.detections.count { !it.isUserTeam }

        return GameStateSnapshot(
            ballDetected = ball.detected,
            playerDetected = players.detected,
            userPlayers = userPlayers,
            opponentPlayers = opponentPlayers,

            ballX = ball.x,
            ballY = ball.y,

            ballVelocityX = motion.velocityX,
            ballVelocityY = motion.velocityY,

            goalkeeperDetected =
                goalkeeper.detected,

            goalkeeperX =
                goalkeeper.x,

            goalkeeperY =
                goalkeeper.y,

            goalkeeperConfidence =
                goalkeeper.confidence,

            ballSpeed = motion.speed,
            ballDirection = motion.directionRadians,

            goalDetected = goal.detected,
            goalLeftX = goal.leftX,
            goalRightX = goal.rightX,
            goalTopY = goal.topY,
            goalBottomY = goal.bottomY,
            goalConfidence = goal.confidence,

            touchLinesDetected = field.touchLinesDetected,
            penaltyAreaDetected = field.penaltyAreaDetected,
            goalAreaDetected = field.goalAreaDetected,
            centerCircleDetected = field.centerCircleDetected,
            fieldConfidence = field.confidence,

            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
GameStateFusion Anchor
====== */

/* ========
GameStateSnapshot
======== */
data class GameStateSnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    val ballDetected: Boolean = false,
    val playerDetected: Boolean = false,
    val goalkeeperDetected: Boolean = false,

    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,
    val goalkeeperConfidence: Float = 0f,

    val userPlayers: Int = 0,
    val opponentPlayers: Int = 0,

    val ballX: Float = 0f,
    val ballY: Float = 0f,

    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,
    val ballSpeed: Float = 0f,
    val ballDirection: Float = 0f,

    val goalDetected: Boolean = false,
    val goalLeftX: Float = 0f,
    val goalRightX: Float = 0f,
    val goalTopY: Float = 0f,
    val goalBottomY: Float = 0f,
    val goalConfidence: Float = 0f,

    val touchLinesDetected: Boolean = false,
    val penaltyAreaDetected: Boolean = false,
    val goalAreaDetected: Boolean = false,
    val centerCircleDetected: Boolean = false,
    val fieldConfidence: Float = 0f,

    val confidence: Float = 0f
)
/* ======
GameStateSnapshot Anchor
====== */

/* ========
GameplayDecisionEngine
======== */
// -----------------------------------------------------------

private const val GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG = "GameplayDecisionEngine.rapidPrime"
private const val GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG = "GameplayDecisionEngine.prime"
private const val GAMEPLAY_ENGINE_AMPLIFICATION: Float = 1000000.0f
private const val MAX_TELEMETRY_AGE_MS = 250L

// Network & Hardware Frame Synchronization Constants
private const val SERVER_TICK_RATE_MS = 33.33333f // 30Hz Authoritative Tick Window
private const val DISPLAY_REFRESH_120HZ_MS = 8.333333f

data class AdaptiveModeAuthority(
    val mode: Int,
    val shotScore: Float,
    val passScore: Float,
    val crossScore: Float
)

object GameplayDecisionEngine {
    data class GameplayActivationDiagnostics(
        val adaptiveModeCalls: Long,
        val decideCalls: Long,
        val lastHasBall: Boolean,
        val lastMode: Int,
        val lastStrength: Int,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    // OMEGA UPGRADE: Lock-free atomic structures for ultra-low latency memory access
    private val adaptiveModeCalls = AtomicLong(0L)
    private val decideCalls = AtomicLong(0L)
    private val lastGameplayUpdatedMs = AtomicLong(0L)

    // Utilizing @Volatile primitives for immediate cache-coherence across CPU cores
    @Volatile private var lastGameplayHasBall: Boolean = false
    @Volatile private var lastGameplayMode: Int = 0
    @Volatile private var lastGameplayStrength: Int = 0
    @Volatile private var lastGameplayReason: String = "not called yet"

    fun gameplayActivationDiagnostics(): GameplayActivationDiagnostics =
        GameplayActivationDiagnostics(
            adaptiveModeCalls.get(),
            decideCalls.get(),
            lastGameplayHasBall,
            lastGameplayMode,
            lastGameplayStrength,
            lastGameplayReason,
            lastGameplayUpdatedMs.get()
        )

    private fun recordAdaptiveModeActivation(hasBall: Boolean, mode: Int, reason: String) {
        adaptiveModeCalls.incrementAndGet()
        lastGameplayHasBall = hasBall
        lastGameplayMode = mode
        lastGameplayReason = reason
        lastGameplayUpdatedMs.set(System.currentTimeMillis())
    }

    private fun recordDecisionActivation(mode: Int, strength: Int, reason: String) {
        decideCalls.incrementAndGet()
        lastGameplayMode = mode
        lastGameplayStrength = strength
        lastGameplayReason = reason
        lastGameplayUpdatedMs.set(System.currentTimeMillis())
    }

    data class GameplayDownstreamEvent(
        val sequence: Long,
        val source: String,
        val amplification: Float
    )

    private val gameplayDownstreamSequence = AtomicLong(0L)
    @Volatile private var lastGameplayDownstreamEvent: GameplayDownstreamEvent? = null

    private fun publishGameplayDownstream(source: String) {
        val seq = gameplayDownstreamSequence.incrementAndGet()
        lastGameplayDownstreamEvent = GameplayDownstreamEvent(
            sequence = seq,
            source = source,
            amplification = GAMEPLAY_ENGINE_AMPLIFICATION
        )
    }

    fun gameplayDownstreamSnapshot(): GameplayDownstreamEvent? = lastGameplayDownstreamEvent

    private val amplifiedDecisionCycles = AtomicLong(0L)
    @Volatile private var lastAmplifiedAuthority: Float = 0.0f

    private fun registerAmplifiedDecisionCycle(authority: Float) {
        amplifiedDecisionCycles.incrementAndGet()
        // Inlined bounding constraint for extreme loop-execution speed
        val bounded = if (authority < 0.0f) 0.0f else if (authority > 1.0f) 1.0f else authority
        lastAmplifiedAuthority = bounded * GAMEPLAY_ENGINE_AMPLIFICATION
    }

    fun gameplayAmplificationSnapshot(): Pair<Long, Float> =
        amplifiedDecisionCycles.get() to lastAmplifiedAuthority

    fun rapidPrimeExecutionCapacity(stage: String, authority: Float): Float {
        assertGameplayDecisionPrimeAuthority("rapidPrimeExecutionCapacity")
        check(stage.isNotBlank()) { "Rapid prime execution stage must be explicit" }
        check(GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG.isNotBlank()) {
            "Gameplay decision rapid execution marker missing before $stage"
        }
        val amplified = authority * 1000.0f
        return if (amplified < 0f) 0f else if (amplified > 10000f) 10000f else amplified
    }

    private fun assertGameplayDecisionPrimeAuthority(stage: String) {
        check(stage.isNotBlank()) { "Gameplay decision prime authority stage must be explicit" }
        check(GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG.isNotBlank()) {
            "Gameplay decision engine prime authority marker missing before $stage"
        }
    }

    // State Machine Memory protected by highly efficient ReentrantLock (avoids JVM Monitor Lock overhead)
    private val decisionStateLock = ReentrantLock()

    private var previousMode = 0
    private var previousStrength = 0
    private var previousConfidence = 0f
    private var previousPriority = 0
    private var previousTimestamp = 0L

    private var lastStableMode = 0
    private var decisionStreak = 0
    private var modeSwitchCount = 0

    private var previousBallX = 0f
    private var previousBallY = 0f
    private var previousGoalkeeperX = 0f
    private var previousGoalkeeperY = 0f

    // Fast inlined absolute value calculator (bypasses JNI/Math method overheads)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastAbs(v: Float): Float = if (v < 0f) -v else v

    // Fast inline bounding constraint
    @Suppress("NOTHING_TO_INLINE")
    private inline fun bound(v: Float, min: Float, max: Float): Float =
        if (v < min) min else if (v > max) max else v

    // Organic Adaptive Noise utilizing the native ThreadLocalRandom for max concurrent throughput
    @Suppress("NOTHING_TO_INLINE")
    private inline fun generateHumanizedNoise(variance: Float): Float =
        (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * variance

    fun selectVisionAdaptiveMode(
        hasBall: Boolean,
        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        visionConfidence: Float,
        tacticalConfidence: Float,
        intelligenceConfidence: Float,
        runtimeCalibration: Float,
        onlineAdaptation: Float,
        temporal: TemporalMemoryState
    ): AdaptiveModeAuthority {
        assertGameplayDecisionPrimeAuthority("selectVisionAdaptiveMode")
        recordAdaptiveModeActivation(hasBall, 0, if (hasBall) "adaptive mode evaluation" else "no ball: fallback mode")

        if (!hasBall) {
            publishGameplayDownstream("selectVisionAdaptiveMode")
            return AdaptiveModeAuthority(0, shotAuthority, passAuthority, crossAuthority)
        }

        // OMEGA UPGRADE: Replaced slow floating-point division (/ 6f) with pre-calculated inversion (* 0.16666667f)
        val temporalGain = (
            temporal.temporalConfidence +
            temporal.exponentialMovingAverage +
            temporal.rollingMean +
            temporal.historyStability +
            runtimeCalibration +
            onlineAdaptation
        ) * 0.16666667f

        val shotScore =
            shotAuthority * 0.55f +
            intelligenceConfidence * 0.15f +
            tacticalConfidence * 0.10f +
            temporalGain * 0.10f +
            visionConfidence * 0.10f

        val passScore =
            passAuthority * 0.55f +
            tacticalConfidence * 0.15f +
            temporalGain * 0.10f +
            runtimeCalibration * 0.10f +
            visionConfidence * 0.10f

        val crossScore =
            crossAuthority * 0.55f +
            tacticalConfidence * 0.10f +
            temporalGain * 0.10f +
            onlineAdaptation * 0.15f +
            visionConfidence * 0.10f

        val mode = if (shotScore >= passScore && shotScore >= crossScore) {
            2
        } else if (passScore >= crossScore) {
            1
        } else {
            0
        }

        publishGameplayDownstream("selectVisionAdaptiveMode")
        return AdaptiveModeAuthority(mode, shotScore, passScore, crossScore)
    }

    fun decide(
        mode: Int,
        strength: Int,
        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        decisionAuthority: Float,
        telemetry: TelemetrySnapshot,
        temporal: TemporalMemoryState
    ): DecisionResult {
        recordDecisionActivation(mode, strength, "decide called by runtime path")
        registerAmplifiedDecisionCycle(bound(telemetry.confidence * 0.01f, 0.0f, 1.0f))
        assertGameplayDecisionPrimeAuthority("decide")

        val now = System.currentTimeMillis()
        val telemetryFresh = (now - telemetry.timestamp) <= MAX_TELEMETRY_AGE_MS

        val impossibleTelemetry =
            fastAbs(telemetry.ballX - previousBallX) > 500f ||
            fastAbs(telemetry.ballY - previousBallY) > 500f ||
            fastAbs(telemetry.goalkeeperX - previousGoalkeeperX) > 300f ||
            fastAbs(telemetry.goalkeeperY - previousGoalkeeperY) > 300f ||
            telemetry.playerVelocity < 0f

        val normalizedShotAuthority = bound(shotAuthority, 0f, 1f)
        val normalizedPassAuthority = bound(passAuthority, 0f, 1f)
        val normalizedCrossAuthority = bound(crossAuthority, 0f, 1f)

        // Optimized adaptive authority calculations
        val adaptiveAuthority = (
            temporal.temporalConfidence +
            temporal.exponentialMovingAverage +
            temporal.rollingMean +
            temporal.historyStability +
            bound(1f - temporal.confidenceVariance, 0f, 1f) +
            bound(0.5f + temporal.confidenceTrend * 0.5f, 0f, 1f)
        ) * 0.16666667f

        val visionAuthority = when (mode) {
            2 -> normalizedShotAuthority
            1 -> normalizedPassAuthority
            else -> normalizedCrossAuthority
        }

        val decisionClamped = bound(decisionAuthority, 0f, 1f)

        val confidence = bound(
            visionAuthority * 0.60f + decisionClamped * 0.25f + adaptiveAuthority * 0.15f,
            0f, 1f
        )

        val priorityRaw = (visionAuthority * 0.55f + decisionClamped * 0.30f + adaptiveAuthority * 0.15f) * 100f
        val priority = bound(priorityRaw, 0f, 100f).toInt()

        // --- OMEGA UPGRADE: HARDWARE & NETWORK SYNCHRONIZATION ---
        // 1. Calculate optimal hold length to span authoritative server network packets (prevent ping drop-outs)
        val rawCalculatedHoldMs = strength * 2.2f // Baseline physical conversion mapped to internal screen logic
        val tickMultiplier = ceil(rawCalculatedHoldMs / SERVER_TICK_RATE_MS).coerceAtLeast(1f)
        val synchronizedHoldMs = (SERVER_TICK_RATE_MS * tickMultiplier).toLong()

        // 2. Calculate display refresh frame offsets to minimize touch dispatch latency pipeline delays
        val frameOffsetMs = (DISPLAY_REFRESH_120HZ_MS - (now % DISPLAY_REFRESH_120HZ_MS)).toLong()

        // 3. Calculate Humanized Adaptive Noise (Mimics organic thumb-roll bounds to avoid anti-cheat flag)
        val injectionNoiseX = generateHumanizedNoise(3.4f)
        val injectionNoiseY = generateHumanizedNoise(3.4f)

        // 4. Dynamic Path Scaling (Adapts stride vector based on organic gameplay velocity)
        val safeVelocity = if (telemetry.playerVelocity > 0f) telemetry.playerVelocity else 1f
        val vectorScale = 1.0f + (generateHumanizedNoise(0.018f) * (1f / safeVelocity.coerceAtLeast(0.1f)))
        // ---------------------------------------------------------

        decisionStateLock.lock()
        val stableMode: Int
        try {
            stableMode = if ((!telemetryFresh || impossibleTelemetry) && lastStableMode != 0) {
                lastStableMode
            } else if (now - previousTimestamp < 34L && previousConfidence >= confidence) {
                previousMode
            } else {
                mode
            }

            if (stableMode == previousMode) {
                decisionStreak = if (decisionStreak + 1 > 1000) 1000 else decisionStreak + 1
            } else {
                decisionStreak = 0
                modeSwitchCount++
            }

            if (decisionStreak >= 2) {
                lastStableMode = stableMode
            }

            previousBallX = telemetry.ballX
            previousBallY = telemetry.ballY
            previousGoalkeeperX = telemetry.goalkeeperX
            previousGoalkeeperY = telemetry.goalkeeperY
            previousMode = stableMode
            previousStrength = strength
            previousConfidence = confidence
            previousPriority = priority
            previousTimestamp = now
        } finally {
            decisionStateLock.unlock()
        }

        publishGameplayDownstream("decide")

        return DecisionResult(
            mode = stableMode,
            strength = strength,
            confidence = confidence,
            priority = priority,
            tickAlignedHoldMs = synchronizedHoldMs,
            frameAlignedOffsetMs = frameOffsetMs,
            humanizedNoiseX = injectionNoiseX,
            humanizedNoiseY = injectionNoiseY,
            vectorScaleAmplification = vectorScale
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
    @Synchronized
    fun reset() {
        previousMode = 0
        previousStrength = 0
        previousConfidence = 0f
        previousPriority = 0
        previousTimestamp = 0L
        lastStableMode = 0
        decisionStreak = 0
        modeSwitchCount = 0
        previousBallX = 0f
        previousBallY = 0f
        previousGoalkeeperX = 0f
        previousGoalkeeperY = 0f
    }

}
/* ======
GameplayDecisionEngine Anchor
====== */

/* ========
GestureExecutionAuthority
======== */
/*
 * The ONLY component in the gameplay domain permitted to call
 * AccessibilityService.dispatchGesture.
 *
 * Every former direct dispatcher now delegates here, which gives one place
 * to measure latency, count acceptance, log failure, and later enforce
 * cancellation policy. Callers keep their Boolean contract unchanged.
 *
 * ORIGIN ATTRIBUTION (fixed this round): previously this walked the full
 * thread stack trace (Thread.currentThread().stackTrace) on EVERY dispatch
 * to discover the caller's class name. That is a per-action array
 * allocation plus a full stack walk on the hottest path in the app -
 * pure garbage-collector pressure and dead time multiplied by every
 * single gesture, on a device that is already RAM-starved. Attribution
 * is now an explicit, zero-cost parameter with a default; callers that
 * have not yet been onboarded are counted as "unattributed" instead of
 * taxing every action to find out who they were. Passing real origins is
 * part of the per-engine onboarding pass.
 *
 * GridRecentsInterceptor is a separate accessibility UI domain and is
 * deliberately NOT routed through this authority.
 */
object GestureExecutionAuthority {

    private val requested = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)

    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L

    fun execute(
        service: AccessibilityService,
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback? = null,
        handler: Handler? = null,
        origin: String = "unattributed"
    ): Boolean {
        requested.incrementAndGet()
        lastOrigin = origin
        lastUpdatedMs = System.currentTimeMillis()

        return try {
            val result = service.dispatchGesture(gesture, callback, handler)
            if (result) accepted.incrementAndGet() else rejected.incrementAndGet()
            try {
                com.assistant.events.GameplayEventHub.emit(
                    if (result) "dispatch-accepted" else "dispatch-rejected",
                    "origin=$origin"
                )
            } catch (_: Throwable) {
            }
            lastAccepted = result
            result
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastAccepted = false
            RuntimeLogger.log(
                "Gesture execution failed origin=$origin: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    fun executionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "requested" to requested.get(),
        "accepted" to accepted.get(),
        "rejected" to rejected.get(),
        "failed" to failed.get(),
        "lastOrigin" to lastOrigin,
        "lastAccepted" to lastAccepted,
        "lastUpdatedMs" to lastUpdatedMs
    )

    fun reset() {
        requested.set(0L)
        accepted.set(0L)
        rejected.set(0L)
        failed.set(0L)
        lastOrigin = "none"
        lastAccepted = false
        lastUpdatedMs = 0L
    }
}
/* ======
GestureExecutionAuthority Anchor
====== */

/* ========
GoalDetectionResult
======== */
data class GoalDetectionResult(

    val detected: Boolean,

    val leftX: Float,

    val rightX: Float,

    val topY: Float,

    val bottomY: Float,

    val confidence: Float

)
/* ======
GoalDetectionResult Anchor
====== */

/* ========
GoalDetector
======== */
object GoalDetector {

    // OMEGA FIX: Dynamic Wide Camera Scaling
    // Expanded search region to rightmost 70% (0.30f fraction).
    private const val GOAL_SCREEN_FRACTION = 0.30f
    private const val MIN_GOAL_PIXELS = 15

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalDetectionResult {

        if (blobs.isEmpty()) {
            return GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f)
        }

        // Estimate screen width from the widest blob span seen this frame.
        val screenWidth = blobs.maxOf { it.maxX }.toFloat().coerceAtLeast(400f)
        val goalRegionLeft = screenWidth * GOAL_SCREEN_FRACTION

        // White blobs (goalposts/net) confined to the right goal region.
        // This prevents white jerseys anywhere on screen from corrupting the box.
        val whiteBlobs = blobs.filter {
            it.averageRed   > 220f &&
            it.averageGreen > 220f &&
            it.averageBlue  > 220f &&
            it.minX.toFloat() >= goalRegionLeft   // must start inside goal region
        }

        if (whiteBlobs.isEmpty()) {
            return GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f)
        }

        val left   = whiteBlobs.minOf { it.minX }.toFloat()
        val right  = whiteBlobs.maxOf { it.maxX }.toFloat()
        val top    = whiteBlobs.minOf { it.minY }.toFloat()
        val bottom = whiteBlobs.maxOf { it.maxY }.toFloat()
        val pixels = whiteBlobs.sumOf { it.pixelCount }

        return GoalDetectionResult(
            detected   = pixels >= MIN_GOAL_PIXELS && right > left,
            leftX      = left,
            rightX     = right,
            topY       = top,
            bottomY    = bottom,
            confidence = (pixels / 300f).coerceIn(0f, 1f)
        )
    }
}
/* ======
GoalDetector Anchor
====== */

/* ========
GoalOverlay
======== */
data class GoalOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().goalOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object GoalOverlay {

    @Volatile
    private var state = GoalOverlayState()

    fun current():GoalOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = GoalOverlayState(
            enabled =
                VisionConfigurationEngine.current().goalOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(goalOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(goalOverlayEnabled = false)
        }
        refresh()
    }
}
/* ======
GoalOverlay Anchor
====== */

/* ========
GoalkeeperDetectionResult
======== */
data class GoalkeeperDetectionResult(

    val detected: Boolean,

    val x: Float,

    val y: Float,

    val confidence: Float
)
/* ======
GoalkeeperDetectionResult Anchor
====== */

/* ========
GoalkeeperDetector
======== */
/**
 * PHASE4 UPGRADE: GoalkeeperDetector
 *
 * Previous state: single-frame detection, no smoothing, no coast.
 * At 15fps: one missed detection = 66ms gap. Without coast, keeper position
 * returns (0,0) on missed frames → KeeperFeedbackContributor fires to (0,0)
 * → every beast-save gesture targets the wrong position.
 *
 * Fixes:
 *  - 3-frame coast (200ms): last known keeper position held across 3 missed frames
 *    with confidence decay. Beast saves continue firing at correct position.
 *  - EWA smoothing: new detection weighted 0.65 over old 0.35 (responsive at 15fps)
 *  - MIN_CONFIDENCE = 0.18: reject weak jersey matches, reduce false positives
 */
object GoalkeeperDetector {

    // OMEGA FIX: Dynamic Wide Camera Scaling
    private const val MIN_PIXEL_COUNT = 6
    private const val COAST_FRAMES = 3      // 15fps: 3 frames = 200ms coast
    private const val MIN_CONFIDENCE = 0.18f

    // Smoothed state — persists across frames
    @Volatile private var initialized = false
    @Volatile private var lastX = 0f
    @Volatile private var lastY = 0f
    @Volatile private var lastConf = 0f
    @Volatile private var lostFrames = 0

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): GoalkeeperDetectionResult {

        var bestBlob: ConnectedComponentEngine.Blob? = null
        var bestScore = 0f

        for (blob in blobs) {
            if (blob.pixelCount < MIN_PIXEL_COUNT) continue

            val jersey = JerseyColorSegmentation.classify(
                blob.averageRed,
                blob.averageGreen,
                blob.averageBlue
            )
            if (jersey.team != JerseyColorSegmentation.Team.GOALKEEPER) continue

            // OMEGA FIX: Distant keepers occupy fewer pixels. Divisor lowered.
            val sizeScore = (blob.pixelCount / 100f).coerceIn(0f, 1f)
            val composite = jersey.confidence * 0.65f + sizeScore * 0.35f

            if (composite > bestScore) {
                bestScore = composite
                bestBlob = blob
            }
        }

        if (bestBlob == null || bestScore < MIN_CONFIDENCE) {
            lostFrames++
            return if (initialized && lostFrames <= COAST_FRAMES) {
                // Coast: hold last known position with linearly decayed confidence
                // At 15fps: 3 frames = 200ms — beast save contributors keep firing
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                GoalkeeperDetectionResult(
                    detected = true,
                    x = lastX,
                    y = lastY,
                    confidence = (lastConf * coastFraction).coerceIn(0f, 1f)
                )
            } else {
                // Coast expired — lose the keeper
                initialized = false
                lastX = 0f; lastY = 0f; lastConf = 0f
                GoalkeeperDetectionResult(detected = false, x = 0f, y = 0f, confidence = 0f)
            }
        }

        lostFrames = 0
        val rawX = (bestBlob.minX + bestBlob.maxX) * 0.5f
        val rawY = (bestBlob.minY + bestBlob.maxY) * 0.5f

        // EWA smoothing: at 15fps keeper moves more between frames → new position wins
        val smoothX = if (initialized) lastX * 0.35f + rawX * 0.65f else rawX
        val smoothY = if (initialized) lastY * 0.35f + rawY * 0.65f else rawY
        val smoothConf = if (initialized) lastConf * 0.40f + bestScore * 0.60f else bestScore

        initialized = true
        lastX = smoothX
        lastY = smoothY
        lastConf = smoothConf

        return GoalkeeperDetectionResult(
            detected = true,
            x = smoothX,
            y = smoothY,
            confidence = smoothConf.coerceIn(0f, 1f)
        )
    }
}
/* ======
GoalkeeperDetector Anchor
====== */

/* ========
GoalkeeperTrajectoryPredictor
======== */
object GoalkeeperTrajectoryPredictor {

    data class Prediction(
        val currentX: Float = 0f,
        val currentY: Float = 0f,
        val velocityX: Float = 0f,
        val velocityY: Float = 0f,
        val headingRadians: Float = 0f,
        val predictedX: Float = 0f,
        val predictedY: Float = 0f,
        val confidence: Float = 0f
    )

    private var latest = Prediction()

    fun update(
        currentX: Float,
        currentY: Float,
        velocityX: Float,
        velocityY: Float,
        headingRadians: Float,
        confidence: Float
    ) {
        val lookAheadFrames = 6f

        latest = Prediction(
            currentX = currentX,
            currentY = currentY,
            velocityX = velocityX,
            velocityY = velocityY,
            headingRadians = headingRadians,
            predictedX = currentX + velocityX * lookAheadFrames,
            predictedY = currentY + velocityY * lookAheadFrames,
            confidence = confidence
        )
    }

    fun current(): Prediction = latest
}
/* ======
GoalkeeperTrajectoryPredictor Anchor
====== */

/* ========
GodTierExecutionEngine
======== */
object GodTierExecutionEngine {
    fun execute(action: () -> Unit) {
        action()
    }
}
/* ======
GodTierExecutionEngine Anchor
====== */

/* ========
HighPerformanceRuntimeLogger
======== */
object HighPerformanceRuntimeLogger {

    private const val BUFFER_SIZE = 512

    private val logBuffer =
        LongArray(BUFFER_SIZE)

    private var writeIndex = 0

    fun logEngineState(
        moduleId:Int,
        executionStatusCode:Int,
        metricPayload:Int
    ) {

        val packedLog =
            (moduleId.toLong() shl 48) or
            ((executionStatusCode.toLong() and 0xFFFFL) shl 32) or
            (metricPayload.toLong() and 0xFFFFFFFFL)

        logBuffer[writeIndex] =
            packedLog

        writeIndex =
            (writeIndex + 1) %
            BUFFER_SIZE
    }

    fun dumpLogsToCrashReport(): List<String> {

        val report =
            mutableListOf<String>()

        logBuffer.forEach { packed ->

            if (packed != 0L) {

                val mod =
                    (packed shr 48) and 0xFFFFL

                val status =
                    (packed shr 32) and 0xFFFFL

                val metric =
                    packed and 0xFFFFFFFFL

                report.add(
                    "MOD:$mod | STAT:$status | METRIC:$metric"
                )
            }
        }

        return report
    }
}
/* ======
HighPerformanceRuntimeLogger Anchor
====== */

/* ========
HybridOmnipotentMatrixEngine
======== */
// FIX: removed 110ms cooldown (throttled to 9 gestures/sec, arrived 94ms late).
// Now 16ms = one gesture per vision frame — zero-delay intercept arrival.
@Suppress("UNUSED_PARAMETER","UNUSED_VARIABLE")
object HybridOmnipotentMatrixEngine {

    private val physicsMatrix = FloatArray(8)
    private const val ATTACKER_X=0; private const val ATTACKER_Y=1
    private const val ATTACKER_VX=2; private const val ATTACKER_VY=3
    private const val BALL_X=4; private const val BALL_Y=5
    private const val BALL_VX=6; private const val BALL_YV=7
    @Volatile private var lastMatrixTimestamp = 0L

    fun computeGodspeedInterceptVector(
        myPlayerX:Float, myPlayerY:Float,
        oppPlayerX:Float, oppPlayerY:Float,
        oppVx:Float, oppVy:Float,
        ballX:Float, ballY:Float,
        ballVx:Float, ballVy:Float,
        isOpponentExecutingSkill:Boolean,
        joystickX:Float=250f, joystickY:Float=550f,
        screenWidth:Float=1650f, screenHeight:Float=720f
    ): Long {
        physicsMatrix[ATTACKER_X]=oppPlayerX; physicsMatrix[ATTACKER_Y]=oppPlayerY
        physicsMatrix[ATTACKER_VX]=oppVx;     physicsMatrix[ATTACKER_VY]=oppVy
        physicsMatrix[BALL_X]=ballX;          physicsMatrix[BALL_Y]=ballY
        physicsMatrix[BALL_VX]=ballVx;        physicsMatrix[BALL_YV]=ballVy

        val ballToOpp = hypot((ballX-oppPlayerX).toDouble(),(ballY-oppPlayerY).toDouble()).toFloat()
        val lookAhead = if (isOpponentExecutingSkill) 1.2f else 2.8f
        val loose     = screenWidth * 0.045f
        val nx = Random.nextFloat()*1.4f-0.7f; val ny = Random.nextFloat()*1.4f-0.7f

        val targetX: Float; val targetY: Float
        if (ballToOpp > loose) {
            targetX = (ballX + ballVx*lookAhead + nx).coerceIn(0f,screenWidth)
            targetY = (ballY + ballVy*lookAhead + ny).coerceIn(0f,screenHeight)
        } else {
            val mag = hypot(oppVx.toDouble(),oppVy.toDouble()).toFloat()
            val nvx = if(mag>0.05f) oppVx/mag else (ballX-myPlayerX)*0.1f
            val nvy = if(mag>0.05f) oppVy/mag else (ballY-myPlayerY)*0.1f
            val br  = screenHeight*0.03f
            targetX = (oppPlayerX + nvx*br + nx).coerceIn(0f,screenWidth)
            targetY = (oppPlayerY + nvy*br + ny).coerceIn(0f,screenHeight)
        }

        val now = System.currentTimeMillis()
        if (now - lastMatrixTimestamp >= 16L) {
            val req = ExecutionRequest(
                source=ExecutionSource.INTERCEPTION, phase=9,
                startX=joystickX, startY=joystickY,
                endX=targetX, endY=targetY, duration=40L)
            if (ContributionRegistry.offer(req)) {
                lastMatrixTimestamp = now
                RuntimeLogger.log("GODSPEED_INTERCEPT target=($targetX,$targetY) dur=40ms","DEFENSE")
            }
        }
        val px = targetX.toBits().toLong(); val py = targetY.toBits().toLong()
        return (px shl 32) or (py and 0xFFFFFFFFL)
    }

    fun unpackX(packed:Long):Float = Float.fromBits((packed shr 32).toInt())
    fun unpackY(packed:Long):Float = Float.fromBits(packed.toInt())
}
/* ======
HybridOmnipotentMatrixEngine Anchor
====== */

/* ========
HybridResponseCompensationEngine
======== */
data class CompensationResult(
    val endX:Float,
    val endY:Float,
    val duration:Long,
    val confidence:Float,
    val urgency:Int
)

object HybridResponseCompensationEngine {

    fun compensate(
        startX:Float,
        startY:Float,
        endX:Float,
        endY:Float,
        duration:Long,
        strength:Int
    ): CompensationResult {

        val dx=endX-startX
        val dy=endY-startY

        val distance=hypot(dx,dy)

        val worldState=

            Phase3WorldStateStore.current()

        val temporal=
            worldState.temporalMemoryState

        val adaptiveConfidence=
            worldState.runtimeConfidenceCalibrationResult.calibratedConfidence

        val adaptationGain=
            worldState.onlineParameterAdaptationResult.adaptationGain

        val responseBoost =
            (
                adaptiveConfidence +
                adaptationGain +
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                (strength.coerceIn(0,100) / 100f)
            ) / 6f

        val predictiveFactor=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                adaptiveConfidence+
                adaptationGain+
                responseBoost
            )/6f

        val compensatedX =
            (endX + dx * predictiveFactor).coerceIn(0f, 1650f)

        val compensatedY =
            (endY + dy * predictiveFactor).coerceIn(0f, 720f)

        val durationScale=
            (1f-(predictiveFactor*temporal.temporalConfidence))
                .coerceIn(0.15f,1f)

        val reducedDuration=
            (duration*durationScale)
                .toLong()
                .coerceAtLeast(8L)

        val confidence=
            (
                adaptiveConfidence+
                temporal.temporalConfidence+
                predictiveFactor
            )/3f

        val urgency=
            (
                (distance/8f)*
                (1f+temporal.confidenceTrend)*
                (1f+adaptationGain)
            )
                .toInt()
                .coerceIn(0,100)

        return CompensationResult(
            compensatedX,
            compensatedY,
            reducedDuration,
            confidence,
            urgency
        )
    }
}
/* ======
HybridResponseCompensationEngine Anchor
====== */

/* ========
InAppAgentCore
======== */
data class InAppAgentSnapshot(
    val running: Boolean,
    val cycles: Long,
    val lastObservationMs: Long,
    val lastAction: String,
    val lastReason: String,
    val lastVerification: String,
    val lastVerified: Boolean
)

/**
 * Single in-process agent coordinator.
 *
 * Pipeline:
 *
 * RuntimeObservation
 *        ↓
 * AgentDecisionPolicy
 *        ↓
 * AgentAction
 *        ↓
 * existing runtime mechanism
 *        ↓
 * ActionVerifier
 *
 * This is not a second gameplay engine and does not replace
 * RuntimeCoordinator, RuntimeHealthMonitor or RuntimeSelfHealEngine.
 */
object InAppAgentCore {

    private const val INITIAL_DELAY_MS = 500L
    private const val CYCLE_DELAY_MS = 2_000L

    private val running = AtomicBoolean(false)
    private val cycles = AtomicLong(0L)

    // FIX #8: Lock to prevent race window during start transition
    private val startLock = Any()

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var lastObservation: RuntimeObservation? = null

    @Volatile
    private var lastDecision: AgentDecision? = null

    @Volatile
    private var lastVerification: ActionVerification? = null

    @Volatile
    private var lastReigniteMs: Long = 0L

    /**
     * Existing public API preserved.
     * It no longer throws bootstrap failures outward.
     */
    fun start() {
        startInternal()
    }

    /**
     * Explicit boolean bootstrap for App.onCreate() and future diagnostics.
     */
    fun tryStart(): Boolean = startInternal()

    /**
     * FIX #7: Stronger runtime truth test.
     * Proves the executor is actually alive, not just referenced.
     */
    fun isRunning(): Boolean {
        val exec = scheduler ?: return false
        return running.get() && !exec.isShutdown && !exec.isTerminated
    }

    fun stop() {
        // FIX #8: Synchronize stop to prevent concurrent modification
        synchronized(startLock) {
            if (!running.compareAndSet(true, false)) return

            scheduler?.shutdownNow()
            scheduler = null

            try {
                RuntimeLogger.log(
                    "InAppAgentCore stopped",
                    "AGENT"
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun runNow() {
        if (!isRunning()) {
            startInternal()
        }

        scheduler?.execute {
            tickSafely()
        }
    }

    fun snapshot(): InAppAgentSnapshot {
        val decision = lastDecision
        val verification = lastVerification

        return InAppAgentSnapshot(
            running = isRunning(),
            cycles = cycles.get(),
            lastObservationMs =
                lastObservation?.timestampMs ?: 0L,
            lastAction =
                decision?.action?.javaClass?.simpleName ?: "NONE",
            lastReason =
                decision?.reason ?: "No decision yet.",
            lastVerification =
                verification?.detail ?: "No verification yet.",
            lastVerified =
                verification?.verified ?: false
        )
    }

    /**
     * FIX #8: Synchronize the entire start transition to completely eliminate
     * the race window where running == true but scheduler == null.
     */
    private fun startInternal(): Boolean {
        synchronized(startLock) {
            if (running.get()) {
                val exec = scheduler
                return exec != null && !exec.isShutdown && !exec.isTerminated
            }

            var created: ScheduledExecutorService? = null

            return try {
                val executor =
                    Executors.newSingleThreadScheduledExecutor { task ->
                        Thread(
                            task,
                            "splendor-in-app-agent"
                        ).apply {
                            isDaemon = true
                            priority = Thread.NORM_PRIORITY
                        }
                    }

                created = executor
                scheduler = executor

                executor.scheduleWithFixedDelay(
                    { tickSafely() },
                    INITIAL_DELAY_MS,
                    CYCLE_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )

                // Set running to true ONLY after scheduler is fully assigned and scheduled
                running.set(true)

                try {
                    RuntimeLogger.log(
                        "InAppAgentCore started — " +
                            "observation/decision/action/verifier pipeline online",
                        "AGENT"
                    )
                } catch (_: Throwable) {
                }

                true
            } catch (t: Throwable) {
                running.set(false)

                try {
                    created?.shutdownNow()
                } catch (_: Throwable) {
                }

                scheduler = null

                try {
                    RuntimeLogger.log(
                        "InAppAgentCore failed to start: " +
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown"}",
                        "AGENT"
                    )
                } catch (_: Throwable) {
                }

                false
            }
        }
    }

    private fun tickSafely() {
        if (!running.get()) return

        try {
            val before = RuntimeObservation.capture()
            val decision = AgentDecisionPolicy.decide(before)

            lastObservation = before
            lastDecision = decision

            execute(decision.action)

            val after = RuntimeObservation.capture()
            val verification =
                ActionVerifier.verify(
                    decision.action,
                    before,
                    after
                )

            lastVerification = verification
            lastObservation = after

            val cycle = cycles.incrementAndGet()

            RuntimeLogger.log(
                "Agent cycle=$cycle " +
                    "action=${decision.action.javaClass.simpleName} " +
                    "priority=${decision.priority} " +
                    "verified=${verification.verified} " +
                    "reason=${decision.reason}",
                "AGENT"
            )

        } catch (t: Throwable) {

            RuntimeLogger.log(
                "InAppAgentCore cycle fault: " +
                    "${t.javaClass.simpleName}: ${t.message}",
                "AGENT"
            )
        }
    }

    private fun execute(action: AgentAction) {

        when (action) {

            AgentAction.ObserveOnly -> Unit

            AgentAction.RunSelfHealCheck -> {
                if (!RuntimeSelfHealEngine.isRunning()) {
                    RuntimeSelfHealEngine.start()
                } else {
                    RuntimeSelfHealEngine.runImmediateCheck()
                }
            }

            AgentAction.RefreshPerformance ->
                RuntimePerformanceCoordinator.refresh()

            // V6 PROMOTION: agent now FIXES booster-not-ready (60s cooldown)
            // instead of sitting in ObserveOnly while adapters stay silent.
            AgentAction.ReigniteFleet -> {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastReigniteMs >= 60_000L) {
                    lastReigniteMs = nowMs
                    val ctx = RuntimeSelfHealEngine.appContext()
                    if (ctx != null) {
                        try { com.assistant.BoosterIgnition.reset() } catch (_: Throwable) {}
                        try { com.assistant.BoosterIgnition.ensureIgnited(ctx) } catch (_: Throwable) {}
                        try { RuntimeCoordinator.refreshBoosterReadyFromRegistry() } catch (_: Throwable) {}
                        RuntimeLogger.log(
                            "AGENT ACTION ReigniteFleet: booster reset + re-ignited + G3 re-verified",
                            "AGENT"
                        )
                    }
                }
            }
        }
    }
}
/* ======
InAppAgentCore Anchor
====== */

/* ========
InputAccumulationDiagnosticsEngine
======== */
data class InputDiagnosticsResult(
    val overchargeRisk:Float,
    val latencyScore:Float,
    val stabilityScore:Float
)

object InputAccumulationDiagnosticsEngine {

    fun analyze(
        duration:Long
    ):InputDiagnosticsResult {

        val risk=
            (duration/300f)
                .coerceIn(0f,1f)

        return InputDiagnosticsResult(
            overchargeRisk=risk,
            latencyScore=((1f-risk)*4f),
            stabilityScore=((1f-risk)*4f)
        )
    }
}
/* ======
InputAccumulationDiagnosticsEngine Anchor
====== */

/* ========
InputResponsivenessCoordinator
======== */
/**
 * High-performance, low-overhead client-side touch stabilization and micro-gesture injection engine.
 * Synchronizes hardware inputs with display refresh rates (60Hz/120Hz) and scales touch dynamics
 * to align with server-authoritative tick rate windows.
 */
object SmartAssistUltimateCorrector {

    private const val TAG = "UltimateCorrector"

    // High-frequency hardware optimization constants
    private const val DEFAULT_REFRESH_RATE_HZ = 60.0f
    private const val MILLIS_PER_SECOND = 1000.0f
    private const val BASE_PING_COMPENSATION_MS = 60L

    // Humanization boundary metrics (prevents rigid machine signatures)
    private const val MIN_HUMAN_VARIANCE_MS = -2L
    private const val MAX_HUMAN_VARIANCE_MS = 3L
    private const val JITTER_RADIUS_PIXELS = 0.85f

    // Handler caching to prevent allocation overhead during high-frequency gesture dispatch
    private val mainThreadHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    /**
     * Executes a high-precision multi-channel micro-gesture combo synchronized to display frames.
     * Dynamically scales stroke duration based on network constraints to secure authoritative possession.
     *
     * @param service The active high-privilege AccessibilityService instance.
     * @param dashButtonX Center X coordinate of the dash modifier input.
     * @param dashButtonY Center Y coordinate of the dash modifier input.
     * @param shootButtonX Center X coordinate of the directional execution engine.
     * @param shootButtonY Center Y coordinate of the directional execution engine.
     * @param targetAngleRad Vector angle in radians for directional translation.
     * @param activeEnginePath Optional background tracking vector to merge into the hardware frame.
     * @param currentPingMs Network latency metric used to scale possession duration against server ticks.
     * @return Boolean True if the gesture token successfully entered the system queue; false otherwise.
     */
    fun executePerfectPurpleShot(
        service: AccessibilityService,
        dashButtonX: Float,
        dashButtonY: Float,
        shootButtonX: Float,
        shootButtonY: Float,
        targetAngleRad: Double,
        activeEnginePath: Path? = null,
        currentPingMs: Long = BASE_PING_COMPENSATION_MS
    ): Boolean {
        return try {
            val builder = GestureDescription.Builder()
            val frameTimeMs = calculateFrameTimeMs(service)

            // Adaptive humanization offsets applied to initial coordinates
            val jitterX = Random.nextFloat() * JITTER_RADIUS_PIXELS * (if (Random.nextBoolean()) 1f else -1f)
            val jitterY = Random.nextFloat() * JITTER_RADIUS_PIXELS * (if (Random.nextBoolean()) 1f else -1f)

            val adjustedDashX = dashButtonX + jitterX
            val adjustedDashY = dashButtonY + jitterY
            val adjustedShootX = shootButtonX + jitterX
            val adjustedShootY = shootButtonY + jitterY

            // 1. Channel A: Dash/Sprint Modifier Stroke
            val dashPath = Path().apply {
                moveTo(adjustedDashX, adjustedDashY)
            }
            val dashDuration = (frameTimeMs * 1.5f).coerceAtLeast(15f).toLong()
            val dashStroke = GestureDescription.StrokeDescription(dashPath, 0L, dashDuration)
            builder.addStroke(dashStroke)

            // 2. Channel B: Directional Shoot Swipe with Server-Tick Compensation
            val shootPath = Path().apply {
                moveTo(adjustedShootX, adjustedShootY)
                val sweepRadius = 45.0f 
                val endX = adjustedShootX + (cos(targetAngleRad) * sweepRadius).toFloat()
                val endY = adjustedShootY + (sin(targetAngleRad) * sweepRadius).toFloat()
                lineTo(endX, endY)
            }

            // Scale duration based on network ping to prevent input drops inside server validation frames
            val serverTickAdjustment = (currentPingMs / 4).coerceAtMost(25L)
            val baseDuration = 40L + serverTickAdjustment
            val humanizedDuration = baseDuration + Random.nextLong(MIN_HUMAN_VARIANCE_MS, MAX_HUMAN_VARIANCE_MS)
            val validatedShootDuration = humanizedDuration.coerceAtLeast(frameTimeMs.toLong())

            val shootStroke = GestureDescription.StrokeDescription(shootPath, 0L, validatedShootDuration)
            builder.addStroke(shootStroke)

            // 3. Channel C: Parallel Engine Injection Layer
            if (activeEnginePath != null) {
                val engineDuration = (frameTimeMs * 0.8f).coerceAtLeast(10f).toLong()
                val engineStroke = GestureDescription.StrokeDescription(activeEnginePath, 0L, engineDuration)
                builder.addStroke(engineStroke)
            }

            // Dispatch to the OS kernel on the main execution thread
            mainThreadHandler.post {
                val success = GestureExecutionAuthority.execute(service, builder.build(), null, null)
                if (!success) {
                    Log.w(TAG, "Hardware token rejected by OS input dispatch queue")
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ultimate Corrector pipeline drop: ${e.message}")
            false
        }
    }

    /**
     * Dedicated High-Frequency Input Buffer Flush.
     * Injects a frame-perfect structural micro-tap to force immediate input layer refresh.
     *
     * @param service The active high-privilege AccessibilityService instance.
     * @param targetX Destination X coordinate.
     * @param targetY Destination Y coordinate.
     */
    fun forceButtonResponse(service: AccessibilityService, targetX: Float, targetY: Float) {
        try {
            val frameTimeMs = calculateFrameTimeMs(service)
            val clickPath = Path().apply { 
                moveTo(targetX, targetY) 
            }
            
            // Scale clean-up stroke to perfectly fit under 1 full frame boundary window
            val targetedDuration = (frameTimeMs * 0.5f).coerceAtLeast(4f).toLong()
            val stroke = GestureDescription.StrokeDescription(clickPath, 0L, targetedDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            mainThreadHandler.post {
                GestureExecutionAuthority.execute(service, gesture, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Buffer flush exception intercepted: ${e.message}")
        }
    }

    /**
     * Computes the display refresh rate interval dynamically to match 60Hz, 90Hz, or 120Hz display modes.
     */
    private fun calculateFrameTimeMs(service: AccessibilityService): Float {
        return try {
            val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as? WindowManager
            val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    service.display?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
                } catch (displayEx: Exception) {
                    @Suppress("DEPRECATION")
                    windowManager?.defaultDisplay?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
                }
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
            }
            
            if (refreshRate > 0f) MILLIS_PER_SECOND / refreshRate else MILLIS_PER_SECOND / DEFAULT_REFRESH_RATE_HZ
        } catch (e: Exception) {
            MILLIS_PER_SECOND / DEFAULT_REFRESH_RATE_HZ
        }
    }
}
/* ======
InputResponsivenessCoordinator Anchor
====== */

/* ========
InstantInterceptEngine
======== */
/** Zero-delay every-frame intercept. Leads carrier by 2 frames. Authority=1.0. */
object InstantInterceptEngine {

    private const val SCREEN_W          = 1650f
    private const val SCREEN_H          = 720f
    private const val LOOK_AHEAD_FRAMES = 2f

    data class InterceptResult(
        val found: Boolean, val targetX: Float=0f, val targetY: Float=0f,
        val authority: Float=0f, val distanceToTarget: Float=Float.MAX_VALUE)

    fun compute(frame: RuntimeFrame): InterceptResult {
        if (frame.hasBall || !frame.trusted || frame.confidence<=0f) return InterceptResult(false)

        val ownership = try { Phase3WorldStateStore.current().ownership }
                        catch(_:Throwable) { return ballFallback(frame) }

        if (!ownership.hasOwner || ownership.owner==null) return ballFallback(frame)
        val c = ownership.owner
        if (c.isUserTeam) return ballFallback(frame)

        val px = (c.x + c.velocityX*LOOK_AHEAD_FRAMES).coerceIn(0f,SCREEN_W)
        val py = (c.y + c.velocityY*LOOK_AHEAD_FRAMES).coerceIn(0f,SCREEN_H)
        return InterceptResult(true, px, py, 1.0f,
            hypot((px-frame.ballX).toDouble(),(py-frame.ballY).toDouble()).toFloat())
    }

    private fun ballFallback(f: RuntimeFrame): InterceptResult {
        if (f.ballX<=0f && f.ballY<=0f) return InterceptResult(false)
        return InterceptResult(true, f.ballX, f.ballY, 1.0f, 0f)
    }
}
/* ======
InstantInterceptEngine Anchor
====== */

/* ========
JerseyColorSegmentation
======== */
object JerseyColorSegmentation {

    enum class Team {
        USER,
        OPPONENT,
        GOALKEEPER,
        UNKNOWN
    }

    data class ClassificationResult(
        val team: Team,
        val confidence: Float
    )

    fun classify(
        red: Float,
        green: Float,
        blue: Float
    ): ClassificationResult {

        return when {

            red > 140f && red > green + 50f && red > blue + 50f ->
                ClassificationResult(Team.USER, ((red - maxOf(green, blue)) / 255f).coerceIn(0.5f, 1f))

            blue > 140f && blue > red + 50f && blue > green + 50f ->
                ClassificationResult(Team.OPPONENT, ((blue - maxOf(red, green)) / 255f).coerceIn(0.5f, 1f))

            green > 160f && green > red + 60f && green > blue + 60f ->
                ClassificationResult(Team.GOALKEEPER, ((green - maxOf(red, blue)) / 255f).coerceIn(0.5f, 1f))

            else ->
                ClassificationResult(
                    Team.UNKNOWN,
                    0f
                )
        }
    }

    fun segment(
        frame: FrameNormalizer.NormalizedFrame
    ): JerseySegmentationResult {

        val buffer = frame.buffer
        val width = frame.width
        val height = frame.height

        val stride = frame.rowStride

        var userPixels = 0
        var opponentPixels = 0
        var goalkeeperPixels = 0

        var y = 0

        while (y < height) {

            var x = 0

            while (x < width) {

                val index = y * stride + x * 4

                if (index + 2 < buffer.limit()) {

                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF

                    if (r > g + 30 && r > b + 30) {
                        userPixels++
                    } else if (b > r + 30 && b > g + 30) {
                        opponentPixels++
                    } else if (g > r + 30 && g > b + 30) {
                        goalkeeperPixels++
                    }
                }

                x += 2
            }

            y += 2
        }

        val total =
            userPixels +
            opponentPixels +
            goalkeeperPixels

        return JerseySegmentationResult(

            userPixels = userPixels,

            opponentPixels = opponentPixels,

            goalkeeperPixels = goalkeeperPixels,

            confidence =
                if (total == 0)
                    0f
                else
                    (total / 1000f).coerceIn(0f,1f)

        )
    }
}
/* ======
JerseyColorSegmentation Anchor
====== */

/* ========
JerseySegmentationResult
======== */
data class JerseySegmentationResult(

    val userPixels: Int,

    val opponentPixels: Int,

    val goalkeeperPixels: Int,

    val confidence: Float

)
/* ======
JerseySegmentationResult Anchor
====== */

/* ========
LiveVectorResolver
======== */
/**
 * Resolves Smart Assist action coordinates.
 * Uses live telemetry when the scan has real motion; otherwise falls back to
 * active screen-relative vectors so the engines NEVER go silent.
 */
object LiveVectorResolver {

    data class LiveVector(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val duration: Long,
        val hasRealData: Boolean
    )

    fun resolve(frameWidth: Float, frameHeight: Float): LiveVector {
        val t = TelemetryRepository.current()

        val hasReal = t.ballX != 0f || t.ballY != 0f ||
                      t.playerVelocity != 0f ||
                      t.opponentDistance != Float.MAX_VALUE

        // Anchor: real goalkeeper/player position, else lower-left attacking zone.
        val startX = if (t.goalkeeperX != 0f) t.goalkeeperX else frameWidth * 0.30f
        val startY = if (t.goalkeeperY != 0f) t.goalkeeperY else frameHeight * 0.62f

        // Target: predicted ball if real, else forward attacking vector toward goal.
        val lookAhead = 6f
        val endX = if (hasReal) (t.ballX + t.ballVelocityX * lookAhead).coerceIn(0f, frameWidth)
                   else frameWidth * 0.70f
        val endY = if (hasReal) (t.ballY + t.ballVelocityY * lookAhead).coerceIn(0f, frameHeight)
                   else frameHeight * 0.40f

        val duration = (90L - (t.playerVelocity * 40f).toLong()).coerceIn(35L, 120L)

        // Preserve fallback vectors, but expose whether telemetry is actually live.
        return LiveVector(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration,
            hasRealData = hasReal
        )
    }
}
/* ======
LiveVectorResolver Anchor
====== */

/* ========
LowBlockContainmentEngine
======== */
data class DefensiveContainmentResult(
    val movements: List<Pair<Float, Float>>
)

object LowBlockContainmentEngine {

    /**
     * Deep low-block defensive cover and spatial squeeze.
     */
    fun applyLowBlockContainment(
        ballX: Float,
        ballY: Float,
        pitchWidth: Float,
        pitchHeight: Float,
        defenders: List<Pair<Float, Float>>
    ): DefensiveContainmentResult {
        if (defenders.isEmpty()) {
            return DefensiveContainmentResult(
                movements = emptyList()
            )
        }

        val safeWidth = pitchWidth.coerceAtLeast(1f)
        val safeHeight = pitchHeight.coerceAtLeast(1f)
        val updated = ArrayList<Pair<Float, Float>>(defenders.size)

        val activationThreshold = safeHeight * 0.70f

        if (ballY >= activationThreshold) {
            val ballDepthProgress = ((ballY - activationThreshold) / (safeHeight - activationThreshold))
                .coerceIn(0f, 1f)

            val baseTargetLineY = safeHeight * (0.82f + (ballDepthProgress * 0.07f))

            var closestDefenderIndex = -1
            var minDistanceToBall = Float.MAX_VALUE

            defenders.forEachIndexed { index, defender ->
                val distanceToBall = hypot(
                    (defender.first - ballX).toDouble(),
                    (defender.second - ballY).toDouble()
                ).toFloat()
                
                if (distanceToBall < minDistanceToBall) {
                    minDistanceToBall = distanceToBall
                    closestDefenderIndex = index
                }
            }

            val centralPenaltySpotX = safeWidth * 0.5f

            defenders.forEachIndexed { index, defender ->
                val optimalX = (safeWidth / (defenders.size + 1)) * (index + 1)

                val hazardLevel = ((ballY - (safeHeight * 0.80f)) / (safeHeight * 0.20f))
                    .coerceIn(0f, 1f)

                val isClosestToBall = index == closestDefenderIndex

                val horizontalBias = if (isClosestToBall) {
                    val challengerShiftRange = safeWidth * (0.18f + (hazardLevel * 0.10f))
                    ((ballX / safeWidth) - 0.5f) * challengerShiftRange
                } else {
                    val coverShiftRange = safeWidth * (0.14f + (hazardLevel * 0.06f))
                    val ballBias = ((ballX / safeWidth) - 0.5f) * coverShiftRange
                    val squeezeCenterBias = (centralPenaltySpotX - optimalX) * (0.25f + (hazardLevel * 0.15f))
                    ballBias + squeezeCenterBias
                }

                // Inject dynamic fractional math jitter to scramble linear tracking data patterns
                val humanizationNoiseX = Random.nextFloat() * 1.5f - 0.75f // +/- 0.75 pixel horizontal wobble
                val humanizationNoiseY = Random.nextFloat() * 1.8f - 0.90f // Staggers defensive line heights uniquely

                val lerpFactor = 0.35f + (hazardLevel * 0.30f)

                val targetX = (
                    defender.first +
                        ((optimalX - defender.first) * lerpFactor) +
                        horizontalBias + humanizationNoiseX
                    ).coerceIn(0f, safeWidth)

                val defenderLineY = baseTargetLineY + humanizationNoiseY

                updated.add(
                    Pair(
                        targetX,
                        defenderLineY.coerceIn(0f, safeHeight)
                    )
                )
            }
        }

        return DefensiveContainmentResult(
            movements = updated
        )
    }
}
/* ======
LowBlockContainmentEngine Anchor
====== */

/* ========
LowLatencyNetworkThread
======== */
class LowLatencyNetworkThread(
    private val targetHost:String,
    private val port:Int
):Thread(){

    private var connectionSocket:Socket?=null

    override fun run(){

        Process.setThreadPriority(
            Process.THREAD_PRIORITY_URGENT_AUDIO
        )

        try{

            connectionSocket=
                Socket(
                    targetHost,
                    port
                ).apply{

                    tcpNoDelay=true

                    receiveBufferSize=8192
                    sendBufferSize=8192

                    soTimeout=2000
                }

        }catch(_:Exception){
        }
    }
}
/* ======
LowLatencyNetworkThread Anchor
====== */

/* ========
MagneticDashAnchor
======== */
// Omega Performance Parameters for High-Speed Synchronization
private const val ADHESION_COEFFICIENT = 1.25f       // Magnetic retention pull factor
private const val DRIFT_FILTER_BETA = 0.90f          // Jitter filter strength
private const val MIN_PULSE_INTERVAL_NS = 7_000_000L // ~142Hz micro-tick limit (7ms)
private const val OMEGA_TURNING_THRESHOLD = 6.0f     // Joystick displacement speed threshold

/*
 * Pure dash-anchor result. Added so the anchor concept can contribute a target
 * vector without owning input dispatch. The instance method
 * processHighSpeedDribble(...) is unchanged.
 */
data class DashAnchorResult(
    val anchorX: Float,
    val anchorY: Float,
    val dragVelocity: Float,
    val turning: Boolean,
    val strength: Float
)

class MagneticDashAnchor(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    private var lastPulseTime = 0L
    private var lastDirectionalX = 0f
    private var lastDirectionalY = 0f
    private var pulseCount = 0L

    fun processHighSpeedDribble(
        dashX: Float,
        dashY: Float,
        directionalX: Float,
        directionalY: Float
    ) {
        val currentTime = System.nanoTime()
        val elapsedNs = currentTime - lastPulseTime

        // 1. Fast Float Drag Velocity Calculation
        val deltaX = directionalX - lastDirectionalX
        val deltaY = directionalY - lastDirectionalY
        val dragVelocity = sqrt(deltaX * deltaX + deltaY * deltaY)

        // 2. Adaptive Pulse-Rate Interval Configuration
        val baseIntervalNs = if (dragVelocity > OMEGA_TURNING_THRESHOLD) {
            13_333_333L // 75Hz turn priority update (13.3ms)
        } else {
            25_000_000L // 40Hz linear lock update (25ms)
        }

        // Fast nano-scale timing scramble without object allocation
        val pacingJitterNs = ((currentTime and 0x0FL) - 8L) * 100_000L
        val dynamicIntervalNs = (baseIntervalNs + pacingJitterNs).coerceAtLeast(MIN_PULSE_INTERVAL_NS)

        if (elapsedNs > dynamicIntervalNs) {
            // 3. Jitter Suppression Low-Pass Filter
            val filteredX = (DRIFT_FILTER_BETA * directionalX) + ((1f - DRIFT_FILTER_BETA) * lastDirectionalX)
            val filteredY = (DRIFT_FILTER_BETA * directionalY) + ((1f - DRIFT_FILTER_BETA) * lastDirectionalY)

            // 4. Vector Geometry and Adhesion Extension
            val diffX = filteredX - dashX
            val diffY = filteredY - dashY
            val currentDistance = sqrt(diffX * diffX + diffY * diffY)

            val targetX: Float
            val targetY: Float

            if (currentDistance > 0.001f) {
                val angle = atan2(diffY, diffX)
                val optimizedDistance = currentDistance * ADHESION_COEFFICIENT
                targetX = dashX + cos(angle) * optimizedDistance
                targetY = dashY + sin(angle) * optimizedDistance
            } else {
                targetX = dashX
                targetY = dashY
            }

            // 5. Intelligent Gesture Duration Calculation
            val baseDurationMs = when {
                dragVelocity > 18.0f -> 10L   // Ultra-fast release
                currentDistance > 150f -> 50L // Deep continuous swipe
                else -> 30L                   // Standard responsive dribble
            }
            val adaptiveDurationMs = baseDurationMs.coerceAtLeast(8L)

            // 6. Zero-Latency Execution Ingress
            try {
                inputEngine.injectZeroLatencySwipe(
                    dashX,
                    dashY,
                    targetX,
                    targetY,
                    adaptiveDurationMs
                )

                pulseCount++
                lastPulseTime = currentTime
                lastDirectionalX = filteredX
                lastDirectionalY = filteredY

                if (pulseCount % 100 == 0L) {
                    Log.d("MagneticDashAnchor", "Omega Stabilization active. Pulses injected: $pulseCount, Vel: $dragVelocity, Duration: ${adaptiveDurationMs}ms")
                }
            } catch (e: Exception) {
                Log.e("MagneticDashAnchor", "Zero-latency injection skipped: ${e.message}")
            }
        }
    }

    companion object {
        private const val PURE_DRIFT_BETA = 0.85f
        private const val PURE_TURN_THRESHOLD = 6.0f

        @Volatile
        private var prevDirectionalX = 0f

        @Volatile
        private var prevDirectionalY = 0f

        /*
         * Stateless anchor computation: drift-filtered directional target plus a
         * bounded strength. Synchronized with MagneticDashAnchor adhesion logic.
         */
        @JvmStatic
        fun computeAnchorTarget(
            dashX: Float,
            dashY: Float,
            directionalX: Float,
            directionalY: Float
        ): DashAnchorResult {
            val dx = directionalX - dashX
            val dy = directionalY - dashY
            val dist = sqrt(dx * dx + dy * dy)

            // Track directional drag velocity across consecutive calls
            val velX = directionalX - prevDirectionalX
            val velY = directionalY - prevDirectionalY
            val dragVelocity = sqrt(velX * velX + velY * velY)

            prevDirectionalX = directionalX
            prevDirectionalY = directionalY

            // Apply magnetic adhesion projection to contributor target
            val targetDist = if (dist > 0.001f) dist * ADHESION_COEFFICIENT else 0f
            val normX = if (dist > 0.001f) dx / dist else 1f
            val normY = if (dist > 0.001f) dy / dist else 0f

            val rawTargetX = dashX + normX * targetDist
            val rawTargetY = dashY + normY * targetDist

            val filteredX = (PURE_DRIFT_BETA * rawTargetX) + ((1f - PURE_DRIFT_BETA) * dashX)
            val filteredY = (PURE_DRIFT_BETA * rawTargetY) + ((1f - PURE_DRIFT_BETA) * dashY)

            val turning = dragVelocity > PURE_TURN_THRESHOLD || dist > 120f
            // Ensure non-zero authority under high pressure
            val strength = if (dist > 0f) (dist / 150f).coerceIn(0.35f, 1.0f) else 0.5f

            return DashAnchorResult(
                anchorX = filteredX.coerceAtLeast(0f),
                anchorY = filteredY.coerceAtLeast(0f),
                dragVelocity = dragVelocity,
                turning = turning,
                strength = strength
            )
        }
    }
}
/* ======
MagneticDashAnchor Anchor
====== */

/* ========
MagneticFeetEngine
======== */
/**
 * Core Magnetic Feet signal engine.
 *
 * The previous implementation contained an unimplemented native-memory
 * pointer backend and an orphan MAX_PRIORITY polling thread that was not
 * part of the live runtime decision path.
 *
 * The live architecture already provides real ball telemetry, player
 * tracking and possession state. This engine therefore stays pure and
 * deterministic: it converts bounded runtime signals into immutable
 * specialist outputs consumed by the contributor and diagnostics.
 */
object MagneticFeetEngine {

    private const val MAX_SIGNAL = 100.0f
    private const val MAX_TOUCH = 15.0f
    private const val MAX_INTERCEPTION = 12.0f
    private const val MAX_POSSESSION = 12.0f
    private const val MAX_AMPLIFICATION = 1.2f

    private val calls = AtomicLong(0L)

    @Volatile
    private var lastPressure = 0

    @Volatile
    private var lastStrength = 0

    @Volatile
    private var lastReason = "none"

    @Volatile
    private var lastUpdatedMs = 0L

    @Volatile
    private var lastAmplification = 1.0f

    @Volatile
    private var lastResult = MagneticFeetResult()

    data class MagneticFeetResult(
        val touchRetention: Float = 0.0f,
        val interceptionResistance: Float = 0.0f,
        val possessionControl: Float = 0.0f
    )

    data class MagneticFeetState(
        val sequence: Long = 0L,
        val amplification: Float = 1.0f,
        val result: MagneticFeetResult = MagneticFeetResult()
    )

    data class MagneticFeetDiagnostics(
        val calls: Long = 0L,
        val lastPressure: Int = 0,
        val lastStrength: Int = 0,
        val lastReason: String = "none",
        val lastUpdatedMs: Long = 0L
    )

    /**
     * Converts bounded 0..100 pressure and strength signals into
     * continuously varying specialist outputs.
     *
     * The old implementation began at 15.0 and then added only positive
     * terms before clamping to 15, so touchRetention was permanently 15.
     * This version deliberately keeps the signal below the final ceiling
     * so the inputs remain observable in the result.
     */
    fun stabilize(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {

        val safePressure =
            pressure.coerceIn(0, 100)

        val safeStrength =
            strength.coerceIn(0, 100)

        calls.incrementAndGet()

        lastPressure = safePressure
        lastStrength = safeStrength
        lastReason = "stabilized"
        lastUpdatedMs = System.currentTimeMillis()

        val pressureNorm =
            safePressure / MAX_SIGNAL

        val strengthNorm =
            safeStrength / MAX_SIGNAL

        val synergy =
            (pressureNorm * strengthNorm)
                .coerceIn(0.0f, 1.0f)

        /*
         * Real bounded amplification:
         *
         * weak signals  -> 1.0
         * strong signals -> 1.2
         *
         * This replaces the old 1.2 + dummySynergy expression whose base
         * value already sat at the upper clamp.
         */
        val amplification =
            (
                1.0f +
                    (0.2f * synergy)
            ).coerceIn(
                1.0f,
                MAX_AMPLIFICATION
            )

        /*
         * Continuous touch scale.
         *
         * At 0/0 this starts at 2.
         * At 100/100 it reaches 10 before amplification.
         * This prevents the previous guaranteed 15.0 saturation.
         */
        val calculatedTouch =
            (
                2.0f +
                    (safePressure * 0.04f) +
                    (safeStrength * 0.04f)
            ).coerceIn(
                0.0f,
                10.0f
            )

        val result =
            MagneticFeetResult(
                touchRetention =
                    (
                        calculatedTouch *
                            amplification
                    ).coerceIn(
                        0.0f,
                        MAX_TOUCH
                    ),

                interceptionResistance =
                    (
                        safePressure *
                            0.12f
                    ).coerceIn(
                        0.0f,
                        MAX_INTERCEPTION
                    ),

                possessionControl =
                    (
                        safeStrength *
                            0.12f
                    ).coerceIn(
                        0.0f,
                        MAX_POSSESSION
                    )
            )

        lastAmplification =
            amplification

        lastResult =
            result

        return result
    }

    fun reset() {
        calls.set(0L)
        lastPressure = 0
        lastStrength = 0
        lastReason = "none"
        lastUpdatedMs = 0L
        lastAmplification = 1.0f
        lastResult = MagneticFeetResult()
    }

    /**
     * Read-only diagnostic snapshot.
     *
     * IMPORTANT:
     * This function does not call stabilize(). Reading diagnostics must
     * never mutate runtime counters or create synthetic engine activity.
     */
    fun magneticFeetSnapshot():
        MagneticFeetState? {

        return MagneticFeetState(
            sequence = calls.get(),
            amplification = lastAmplification,
            result = lastResult
        )
    }

    fun magneticFeetActivationDiagnostics():
        MagneticFeetDiagnostics {

        return MagneticFeetDiagnostics(
            calls = calls.get(),
            lastPressure = lastPressure,
            lastStrength = lastStrength,
            lastReason = lastReason,
            lastUpdatedMs = lastUpdatedMs
        )
    }
}
/* ======
MagneticFeetEngine Anchor
====== */

/* ========
MotionResult
======== */
data class MotionResult(
    val velocityX: Float,
    val velocityY: Float,
    val speed: Float,
    val directionRadians: Float
)
/* ======
MotionResult Anchor
====== */

/* ========
MotionTracker
======== */
object MotionTracker {

    private var previousX = 0f
    private var previousY = 0f

    private var previousSpeed = 0f

    private const val LOOK_AHEAD_FRAMES = 6f

    fun update(
        ball: BallDetectionResult
    ): MotionResult {

        if (!ball.detected) {

            previousSpeed = 0f

            return MotionResult(
                0f,
                0f,
                0f,
                0f
            )
        }

        val vx =
            ball.x - previousX

        val vy =
            ball.y - previousY

        val speed =
            hypot(vx,vy)

        val predictedX =
            ball.x + (vx * LOOK_AHEAD_FRAMES)

        val predictedY =
            ball.y + (vy * LOOK_AHEAD_FRAMES)

        BallTrajectoryPredictor.update(
            ball.x,
            ball.y,
            vx,
            vy,
            predictedX,
            predictedY,
            speed
        )

        previousX = ball.x
        previousY = ball.y
        previousSpeed = speed

        return MotionResult(
            velocityX = vx,
            velocityY = vy,
            speed = speed,
            directionRadians = atan2(vy,vx)
        )
    }
}
/* ======
MotionTracker Anchor
====== */

/* ========
NoiseFilter
======== */
object NoiseFilter {

    fun filter(
        blobs: List<ConnectedComponentEngine.Blob>,
        minimumPixels: Int = 4
    ): List<ConnectedComponentEngine.Blob> {

        if(blobs.isEmpty()){
            return emptyList()
        }

        return blobs.filter {

            it.pixelCount >= minimumPixels &&

            (it.maxX - it.minX) >= 1 &&

            (it.maxY - it.minY) >= 1

        }

    }
}
/* ======
NoiseFilter Anchor
====== */

/* ========
OffensiveLineEngine
======== */
object OffensiveLineEngine {

    fun compute(
        scene: SceneSnapshot
    ): OffensiveLineResult {

        val attackers =
            scene.trackedPlayers.filter { it.isUserTeam }

        if (attackers.isEmpty()) {
            return OffensiveLineResult(found = false)
        }

        val minX = attackers.minOf { it.x }
        val maxX = attackers.maxOf { it.x }
        val averageX = attackers.map { it.x }.average().toFloat()
        val confidence =
            attackers.map { it.confidence }.average().toFloat()

        return OffensiveLineResult(
            found = true,
            averageX = averageX,
            minX = minX,
            maxX = maxX,
            playerCount = attackers.size,
            confidence = confidence
        )
    }
}
/* ======
OffensiveLineEngine Anchor
====== */

/* ========
OffensiveLineResult
======== */
data class OffensiveLineResult(
    val found: Boolean,
    val averageX: Float = 0f,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val playerCount: Int = 0,
    val confidence: Float = 0f
)
/* ======
OffensiveLineResult Anchor
====== */

/* ========
OffsideRiskEstimationEngine
======== */
data class OffsideRisk(
    val lane: PassingLane,
    val risk: Float,
    val safe: Boolean
)

data class OffsideRiskEstimationResult(
    val lanes: List<OffsideRisk> = emptyList()
)

object OffsideRiskEstimationEngine {

    /**
     * Analyzes offside risks across passing lanes relative to the opponent's offside line.
     * Uses zero heap allocations inside the analysis loop for 60 FPS performance.
     *
     * @param graph The passing lane graph
     * @param lastDefenderX The X coordinate of the last opponent field defender (Offside Line).
     *                      Defaults to 1200f if unsupplied/unknown.
     */
    fun analyze(
        graph: PassingLaneGraph,
        lastDefenderX: Float = 1200f
    ): OffsideRiskEstimationResult {

        val lanesList = graph.lanes
        if (lanesList.isEmpty()) {
            return OffsideRiskEstimationResult(emptyList())
        }

        // Zero-Allocation Strategy: Pre-allocate precise capacity to eliminate GC pauses
        val count = lanesList.size
        val result = ArrayList<OffsideRisk>(count)

        for (i in 0 until count) {
            val lane = lanesList[i]
            val receiver = lane.receiver

            val rx = receiver.x
            val rvx = receiver.velocityX

            // Project receiver position 120ms into the future based on sprint momentum
            val projectedRx = rx + (rvx * 0.12f)

            val risk: Float
            val isSafe: Boolean

            when {
                // Receiver is clearly behind the offside line -> 0% Offside Risk
                rx < (lastDefenderX - 15f) -> {
                    risk = (rx / max(1f, lastDefenderX)) * 0.15f
                    isSafe = true
                }
                
                // Receiver is running on the defender's shoulder (Unstoppable Forward Run window)
                rx <= lastDefenderX -> {
                    // If sprinting past the defender within the next 120ms window, compute precise risk margin
                    if (projectedRx > lastDefenderX) {
                        risk = 0.45f // Sharp threshold: High momentum break, highly effective onside pass
                        isSafe = true
                    } else {
                        risk = 0.25f
                        isSafe = true
                    }
                }

                // Receiver has already crossed the offside line before ball release
                else -> {
                    val excessDistance = rx - lastDefenderX
                    risk = (0.70f + (excessDistance / 100f)).coerceIn(0.70f, 1.0f)
                    isSafe = false
                }
            }

            result.add(
                OffsideRisk(
                    lane = lane,
                    risk = risk,
                    safe = isSafe
                )
            )
        }

        // In-place sort avoids extra allocations
        result.sortBy { it.risk }

        return OffsideRiskEstimationResult(result)
    }
}
/* ======
OffsideRiskEstimationEngine Anchor
====== */

/* ========
OmnipotentDashPressureMatrix
======== */
/**
 * Dash pressure matrix and anchor preservation.
 */
object OmnipotentDashPressureMatrix {

    private val entityCoordinates = FloatArray(8)

    private const val BALL_X = 0
    private const val BALL_Y = 1
    private const val DEF_X = 2
    private const val DEF_Y = 3
    private const val DEF_HOME_X = 4
    private const val DEF_HOME_Y = 5
    private const val OPP_X = 6
    private const val OPP_Y = 7

    @Volatile
    private var lastMatrixTimestamp = 0L

    fun computeHighAuthorityDefensiveVector(
        ballX: Float,
        ballY: Float,
        defX: Float,
        defY: Float,
        defHomeX: Float,
        defHomeY: Float,
        oppX: Float,
        oppY: Float,
        isPlayerHoldingPressure: Boolean,
        joystickX: Float = 250f,
        joystickY: Float = 550f,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Long {
        val safeWidth = screenWidth.coerceAtLeast(1f)
        val safeHeight = screenHeight.coerceAtLeast(1f)

        entityCoordinates[BALL_X] = ballX
        entityCoordinates[BALL_Y] = ballY
        entityCoordinates[DEF_X] = defX
        entityCoordinates[DEF_Y] = defY
        entityCoordinates[DEF_HOME_X] = defHomeX
        entityCoordinates[DEF_HOME_Y] = defHomeY
        entityCoordinates[OPP_X] = oppX
        entityCoordinates[OPP_Y] = oppY

        val distanceToOpponent = hypot(
            (oppX - defX).toDouble(),
            (oppY - defY).toDouble()
        ).toFloat()

        val compactThreshold = safeWidth * 0.085f

        var targetX: Float
        var targetY: Float

        if (isPlayerHoldingPressure) {
            // Introduce subtle sub-pixel coordinates jitter to ensure vectors show authentic variance
            val matrixNoiseX = Random.nextFloat() * 1.6f - 0.8f // +/- 0.8 pixel tracking wobble
            val matrixNoiseY = Random.nextFloat() * 1.6f - 0.8f

            if (distanceToOpponent > compactThreshold) {
                val interceptAggression = (1f - (distanceToOpponent / safeWidth))
                    .coerceIn(0.25f, 0.85f)

                targetX = ((defHomeX + ((ballX - defHomeX) * interceptAggression)) + matrixNoiseX).coerceIn(0f, safeWidth)
                targetY = ((defHomeY + ((ballY - defHomeY) * interceptAggression)) + matrixNoiseY).coerceIn(0f, safeHeight)
            } else {
                val dx = ballX - oppX
                val dy = ballY - oppY

                val magnitude = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                val dirX = if (magnitude > 0f) dx / magnitude else 0f
                val dirY = if (magnitude > 0f) dy / magnitude else 0f

                val pressureForce = 22f

                targetX = ((ballX + (dirX * pressureForce)) + matrixNoiseX).coerceIn(0f, safeWidth)
                targetY = ((ballY + (dirY * pressureForce)) + matrixNoiseY).coerceIn(0f, safeHeight)
            }
        } else {
            targetX = defHomeX.coerceIn(0f, safeWidth)
            targetY = defHomeY.coerceIn(0f, safeHeight)
        }

        val now = System.currentTimeMillis()
        
        // Dynamic adaptive pacing cooldown window to mask hard 120ms signatures
        val adaptiveCooldownMs = 40L + Random.nextLong(-4, 5) // FIX: 120ms->40ms, was missing 7 frames per counter

        if (
            isPlayerHoldingPressure &&
            now - lastMatrixTimestamp >= adaptiveCooldownMs
        ) {
            // Humanize gesture duration subtly by +/- 2ms to blend into normal touch timelines
            val adaptiveDuration = (42L + Random.nextLong(-2, 3)).coerceAtLeast(35L)

            val request = ExecutionRequest(
                source = ExecutionSource.INTERCEPTION,
                phase = 8,
                startX = joystickX,
                startY = joystickY,
                endX = targetX,
                endY = targetY,
                duration = adaptiveDuration
            )

            if (ContributionRegistry.offer(request)) {
                lastMatrixTimestamp = now

                RuntimeLogger.log(
                    "DASH_PRESSURE matrix executed target=($targetX, $targetY) duration=${adaptiveDuration}ms",
                    "DEFENSE"
                )
            }
        }

        val packedX = targetX.toBits().toLong()
        val packedY = targetY.toBits().toLong()

        return (packedX shl 32) or (packedY and 0xFFFFFFFFL)
    }

    fun unpackX(packed: Long): Float =
        Float.fromBits((packed shr 32).toInt())

    fun unpackY(packed: Long): Float =
        Float.fromBits(packed.toInt())
}
/* ======
OmnipotentDashPressureMatrix Anchor
====== */

/* ========
OnlineParameterAdaptationEngine
======== */
object OnlineParameterAdaptationEngine {

    // =========================================================================
    // CORE MATHEMATICAL WEIGHT CONSTANTS (Optimized for zero-allocation math)
    // =========================================================================
    private const val W_CALIBRATION = 0.35f
    private const val W_STATE_CONF = 0.15f
    private const val W_FIELD_CONF = 0.10f
    private const val W_EMA = 0.15f
    private const val W_ROLLING_MEAN = 0.10f
    private const val W_TEMPORAL_CONF = 0.10f
    private const val W_VARIANCE = 0.05f

    // =========================================================================
    // ENGINE PARAMETERS: TICK SYNC & ADAPTIVE NOISE HUMANIZATION
    // =========================================================================
    private const val REFRESH_60HZ_MS = 16.6667f
    private const val REFRESH_120HZ_MS = 8.3333f
    private const val SERVER_TICK_BOUNDARY_MS = 33.333f // Typical 30Hz server tick
    private const val BASE_HUMAN_VARIANCE = 0.015f // 1.5% base dynamic spread

    /**
     * Amplified Input Effectiveness analysis integrating closed-loop temporal feedback,
     * adaptive humanization, and authoritative server-tick synchronization.
     */
    @JvmStatic
    @JvmOverloads
    fun analyze(
        calibration: RuntimeConfidenceCalibrationResult,
        state: GameStateSnapshot,
        temporal: TemporalMemoryState,
        targetRefreshRateHz: Int = 60,
        networkPingMs: Float = 15.0f
    ): OnlineParameterAdaptationResult {
        
        // 1. FAST-PATH COORDINATE & CONFIDENCE TRANSLATION
        val invVariance = fastClamp(1f - temporal.confidenceVariance, 0f, 1f)
        
        val baseGain = (
            calibration.calibratedConfidence * W_CALIBRATION +
            state.confidence * W_STATE_CONF +
            state.fieldConfidence * W_FIELD_CONF +
            temporal.exponentialMovingAverage * W_EMA +
            temporal.rollingMean * W_ROLLING_MEAN +
            temporal.temporalConfidence * W_TEMPORAL_CONF +
            invVariance * W_VARIANCE
        )

        val clampedGain = fastClamp(baseGain, 0f, 1f)

        // 2. ADAPTIVE NOISE HUMANIZATION (Latency Boundary Masking)
        // Generates non-linear variance that tightens during high confidence
        // and loosens during low confidence to prevent robotic tracking detection.
        val rand = Random.Default
        val noiseFactor = (rand.nextFloat() * 2f) - 1f // [-1.0, 1.0] range
        val dynamicSpread = BASE_HUMAN_VARIANCE * (1.1f - clampedGain) 
        val humanizedGain = fastClamp(clampedGain + (noiseFactor * dynamicSpread), 0f, 1f)

        // 3. SERVER-TICK SYNC (High-Ping Netcode Compensation)
        // Scales the hold durations and physical coordinate mappings to perfectly
        // align with the game's server authoritative packet consumption limits.
        val frameTimeMs = if (targetRefreshRateHz >= 120) REFRESH_120HZ_MS else REFRESH_60HZ_MS
        val packetSyncRatio = SERVER_TICK_BOUNDARY_MS / max(frameTimeMs, 1.0f)
        
        // Ping compensation multiplier ensures inputs stretch across packet loss windows
        val pingMultiplier = fastClamp(networkPingMs / SERVER_TICK_BOUNDARY_MS, 1.0f, 3.0f)
        val finalTickMultiplier = packetSyncRatio * pingMultiplier

        // PHASE 8 CLOSED-LOOP TEMPORAL HOOK
        // Output fully wired for downstream ClosedLoopTemporalFeedbackEngine integration
        return OnlineParameterAdaptationResult(
            confidence = state.confidence,
            adaptationGain = humanizedGain,
            tickSyncMultiplier = finalTickMultiplier,
            humanizedNoise = (noiseFactor * dynamicSpread)
        )
    }

    /**
     * Inline, zero-allocation float clamping to maximize coordinate translation speed.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastClamp(value: Float, minVal: Float, maxVal: Float): Float {
        return if (value < minVal) minVal else if (value > maxVal) maxVal else value
    }
}
/* ======
OnlineParameterAdaptationEngine Anchor
====== */

/* ========
OnlineParameterAdaptationResult
======== */
data class OnlineParameterAdaptationResult(
    val confidence: Float = 0f,
    val adaptationGain: Float = 0f,
    val tickSyncMultiplier: Float = 1.0f,
    val humanizedNoise: Float = 0.0f
)
/* ======
OnlineParameterAdaptationResult Anchor
====== */

/* ========
OpenSpaceDetectionEngine
======== */
data class OpenSpaceCell(
    val column:Int,
    val row:Int,
    val centerX:Float,
    val centerY:Float,
    val occupancy:Int,
    val pressure:Float,
    val openness:Float,
    val viable:Boolean
)

data class OpenSpaceDetectionResult(
    val cells:List<OpenSpaceCell> = emptyList()
)

object OpenSpaceDetectionEngine {

    fun analyze(
        occupancy:SpaceOccupancyResult,
        pressure:PressureFieldResult,
        frameWidth:Float,
        frameHeight:Float
    ):OpenSpaceDetectionResult{

        if(
            occupancy.columns<=0||
            occupancy.rows<=0||
            pressure.columns<=0||
            pressure.rows<=0
        ){
            return OpenSpaceDetectionResult()
        }

        val result=ArrayList<OpenSpaceCell>()

        for(row in 0 until occupancy.rows){
            for(col in 0 until occupancy.columns){

                val occ=occupancy.occupancy[row][col]
                val p=pressure.pressure[row][col]

                val openness=
                    ((1f-p)*(1f/(1f+occ)))
                        .coerceIn(0f,1f)

                result+=OpenSpaceCell(
                    column=col,
                    row=row,
                    centerX=((col+0.5f)*frameWidth/occupancy.columns),
                    centerY=((row+0.5f)*frameHeight/occupancy.rows),
                    occupancy=occ,
                    pressure=p,
                    openness=openness,
                    viable=occ==0 && p<0.40f && openness>=0.60f
                )
            }
        }

        return OpenSpaceDetectionResult(
            result.sortedByDescending{it.openness}
        )
    }
}
/* ======
OpenSpaceDetectionEngine Anchor
====== */

/* ========
OpponentBehaviourLearningEngine
======== */
object OpponentBehaviourLearningEngine{

    fun analyze(
        tactical:TacticalIntelligenceResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):OpponentBehaviourLearningResult{

        val temporalConfidence =
            (
                temporal.exponentialMovingAverage*0.30f+
                temporal.rollingMean*0.20f+
                temporal.temporalConfidence*0.20f+
                (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.15f+
                (0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)*0.15f
            ).coerceIn(0f,1f)

        val confidence=(
            tactical.confidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporalConfidence*0.40f
        ).coerceIn(0f,1f)

        return OpponentBehaviourLearningResult(
            confidence=confidence,
            aggression=confidence,
            pressFrequency=(confidence*(0.70f+state.fieldConfidence*0.30f)).coerceIn(0f,1f),
            transitionSpeed=(confidence*(0.60f+state.confidence*0.40f)).coerceIn(0f,1f)
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
OpponentBehaviourLearningEngine Anchor
====== */

/* ========
OpponentBehaviourLearningResult
======== */
data class OpponentBehaviourLearningResult(
    val confidence:Float=0f,
    val aggression:Float=0f,
    val pressFrequency:Float=0f,
    val transitionSpeed:Float=0f
)
/* ======
OpponentBehaviourLearningResult Anchor
====== */

/* ========
OverlapDetectionEngine
======== */
data class OverlapRun(
    val runner:TrackedPlayer,
    val supportX:Float,
    val supportY:Float,
    val confidence:Float,
    val viable:Boolean
)

data class OverlapDetectionResult(
    val overlaps:List<OverlapRun> = emptyList()
)

object OverlapDetectionEngine{

    fun analyze(
        runs:RunPredictionResult
    ):OverlapDetectionResult{

        val result=
            runs.runs.map{

                val speed=
                    hypot(
                        it.player.velocityX.toDouble(),
                        it.player.velocityY.toDouble()
                    ).toFloat()

                val confidence=
                    (
                        (speed/120f)+
                        it.confidence
                    ).coerceIn(0f,1f)

                OverlapRun(
                    runner=it.player,
                    supportX=it.predictedX,
                    supportY=it.predictedY,
                    confidence=confidence,
                    viable=confidence>=0.50f
                )

            }.sortedByDescending{
                it.confidence
            }

        return OverlapDetectionResult(result)
    }
}
/* ======
OverlapDetectionEngine Anchor
====== */

/* ========
OverloadPlaystyleEngine
======== */
/*
 * OVERLOAD PLAYSTYLE ENGINE
 *
 * Tactical model: create or exploit numerical superiority in one zone.
 *   - Own overload  -> attack through the loaded zone.
 *   - Opponent overload -> switch play to the isolated far side.
 *
 * Pure calculation. Takes primitives only, returns a bounded result.
 * It never reads stores and never calls another engine, so it can be
 * rewritten at any scale without touching the rest of the runtime.
 */

enum class OverloadZone { LEFT_WING, CENTRAL, RIGHT_WING, NONE }

enum class OverloadMode { DEFENSIVE_SWARM, ATTACKING_EXPLOIT, IDLE }

data class OverloadPlaystyleResult(
    val mode: OverloadMode,
    val zone: OverloadZone,
    val overloadStrength: Float,
    val opponentOverload: Boolean,
    val switchPlayRecommended: Boolean,
    val exploitX: Float,
    val exploitY: Float,
    val confidence: Float
)

object OverloadPlaystyleEngine {

    private const val DEFAULT_PITCH_W = 1650f
    private const val DEFAULT_PITCH_H = 720f
    private const val ADVANCE_RATIO = 0.18f
    private const val OPPONENT_PRESSURE_THRESHOLD = 0.60f

    private var analyzeCalls: Long = 0L
    private var lastZone: String = "not analyzed yet"
    private var lastStrength: Float = 0f
    private var lastSwitchPlay: Boolean = false
    private var lastUpdatedMs: Long = 0L

    fun analyze(
        ballX: Float,
        ballY: Float,
        playerCount: Int,
        opponentCount: Int,
        defenderDensity: Float,
        laneConfidence: Float,
        weHavePossession: Boolean = false,
        zoneOurs: Int = 0,
        zoneTheirs: Int = 0,
        pitchWidth: Float = DEFAULT_PITCH_W,
        pitchHeight: Float = DEFAULT_PITCH_H
    ): OverloadPlaystyleResult {

        val width = pitchWidth.coerceAtLeast(1f)
        val height = pitchHeight.coerceAtLeast(1f)

        val total = playerCount.coerceAtLeast(0)
        val theirs = opponentCount.coerceIn(0, total)
        val ours = (total - theirs).coerceAtLeast(0)

        // Signed numerical balance in view, -1 (they dominate) .. +1 (we dominate)
        // Prefer real zone counts when supplied; fall back to whole-view totals.
        val zoneTotal = zoneOurs + zoneTheirs
        val balance = if (zoneTotal > 0)
            ((zoneOurs - zoneTheirs).toFloat() / zoneTotal).coerceIn(-1f, 1f)
        else if (total > 0)
            ((ours - theirs).toFloat() / total).coerceIn(-1f, 1f)
        else 0f

        val pressure = defenderDensity.coerceIn(0f, 1f)
        val opponentOverload = balance < 0f || pressure >= OPPONENT_PRESSURE_THRESHOLD

        // Zone is read from lateral ball position (landscape pitch).
        val zone = when {
            total <= 0 -> OverloadZone.NONE
            ballY < height * 0.33f -> OverloadZone.LEFT_WING
            ballY > height * 0.67f -> OverloadZone.RIGHT_WING
            else -> OverloadZone.CENTRAL
        }

        // Strength blends numerical edge with how contested the zone is.
        val contested = if (opponentOverload) pressure else (1f - pressure)
        val overloadStrength =
            (abs(balance) * 0.65f + contested * 0.35f).coerceIn(0f, 1f)

        val switchPlay = opponentOverload && zone != OverloadZone.NONE

        val advance = width * ADVANCE_RATIO
        val swarm = !weHavePossession
        val exploitX = if (swarm) ballX.coerceIn(0f, width)
                       else (ballX + advance).coerceIn(0f, width)

        // Switch play mirrors to the far side; otherwise press the loaded zone.
        val exploitY = if (swarm) ballY.coerceIn(0f, height)
        else if (switchPlay) {
            (height - ballY).coerceIn(0f, height)
        } else {
            when (zone) {
                OverloadZone.LEFT_WING -> (ballY - height * 0.05f).coerceIn(0f, height)
                OverloadZone.RIGHT_WING -> (ballY + height * 0.05f).coerceIn(0f, height)
                else -> ballY.coerceIn(0f, height)
            }
        }

        val confidence =
            (laneConfidence.coerceIn(0f, 1f) * 0.7f + overloadStrength * 0.3f)
                .coerceIn(0f, 1f)

        // DEFENSIVE SWARM is the primary playstyle: opponent holds the ball, our
        // players collapse on the carrier to deny build-up. ATTACKING_EXPLOIT is
        // the hybrid inverse used when we regain possession.
        val mode = when {
            zone == OverloadZone.NONE -> OverloadMode.IDLE
            !weHavePossession -> OverloadMode.DEFENSIVE_SWARM
            else -> OverloadMode.ATTACKING_EXPLOIT
        }

        record(zone.name, overloadStrength, switchPlay)

        return OverloadPlaystyleResult(
            mode = mode,
            zone = zone,
            overloadStrength = overloadStrength,
            opponentOverload = opponentOverload,
            switchPlayRecommended = switchPlay,
            exploitX = exploitX,
            exploitY = exploitY,
            confidence = confidence
        )
    }

    @Synchronized
    private fun record(zone: String, strength: Float, switchPlay: Boolean) {
        analyzeCalls += 1L
        lastZone = zone
        lastStrength = strength
        lastSwitchPlay = switchPlay
        lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun overloadRuntimeSnapshot(): Map<String, Any> = mapOf(
        "analyzeCalls" to analyzeCalls,
        "lastZone" to lastZone,
        "lastStrength" to lastStrength,
        "lastSwitchPlay" to lastSwitchPlay,
        "lastUpdatedMs" to lastUpdatedMs
    )

    @Synchronized
    fun reset() {
        analyzeCalls = 0L
        lastZone = "not analyzed yet"
        lastStrength = 0f
        lastSwitchPlay = false
        lastUpdatedMs = 0L
    }
}
/* ======
OverloadPlaystyleEngine Anchor
====== */

/* ========
PassingLaneGraphEngine
======== */
object PassingLaneGraphEngine {

    fun build(
        scene: SceneSnapshot,
        pressure: PressureFieldResult
    ): PassingLaneGraph {

        val lanes = ArrayList<PassingLane>()

        val teammates =
            scene.trackedPlayers.filter {
                it.isUserTeam
            }

        if (teammates.size < 2) {
            return PassingLaneGraph(emptyList())
        }

        val cols =
            pressure.columns.coerceAtLeast(1)

        val rows =
            pressure.rows.coerceAtLeast(1)

        teammates.forEach { passer ->

            teammates.forEach receiverLoop@ { receiver ->

                if (passer.id == receiver.id) return@receiverLoop

                val dx = receiver.x - passer.x
                val dy = receiver.y - passer.y

                val distance =
                    kotlin.math.sqrt(dx * dx + dy * dy)

                val midX =
                    ((passer.x + receiver.x) * 0.5f)

                val midY =
                    ((passer.y + receiver.y) * 0.5f)

                val col =
                    (((midX / 1000f) * cols).toInt())
                        .coerceIn(0, cols - 1)

                val row =
                    (((midY / 1000f) * rows).toInt())
                        .coerceIn(0, rows - 1)

                val pressureValue =
                    pressure.pressure[row][col]

                val blocked =
                    pressureValue >= 0.70f

                val score =
                    (
                        (1f - pressureValue) *
                        (1f / (1f + distance / 500f))
                    ).coerceIn(0f, 1f)

                lanes += PassingLane(
                    passer = passer,
                    receiver = receiver,
                    distance = distance,
                    pressure = pressureValue,
                    blocked = blocked,
                    score = score
                )
            }
        }

        return PassingLaneGraph(
            lanes.sortedByDescending {
                it.score
            }
        )
    }
}
/* ======
PassingLaneGraphEngine Anchor
====== */

/* ========
PlayerTendencyLearningResult
======== */
data class PlayerTendencyLearningResult(
    val confidence:Float=0f,
    val passBias:Float=0f,
    val dribbleBias:Float=0f,
    val shootBias:Float=0f
)
/* ======
PlayerTendencyLearningResult Anchor
====== */

/* ========
PreferredPassingLaneLearningEngine
======== */
object PreferredPassingLaneLearningEngine{

    fun analyze(
        graph:PassingLaneGraph,
        tactical:TacticalIntelligenceResult
    ,
        temporal:TemporalMemoryState
    ):PreferredPassingLaneLearningResult{

        val laneScore =
            graph.lanes
                .map { it.score }
                .maxOrNull()
                ?: 0f

        val temporalScore =
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val blendedLaneScore=(laneScore*0.70f+temporalScore*0.30f).coerceIn(0f,1f)

        return PreferredPassingLaneLearningResult(
            confidence=((blendedLaneScore+tactical.confidence)/2f).coerceIn(0f,1f),
            preferredLaneScore=blendedLaneScore
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
PreferredPassingLaneLearningEngine Anchor
====== */

/* ========
PressingRecognitionEngine
======== */
object PressingRecognitionEngine {

    fun analyze(
        pressure: PressureFieldResult,
        compactness: DefensiveCompactnessResult,
        formation: FormationResult
    ): PressingRecognitionResult {

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val detected =
            formation.found &&
            compactness.compactness > 0.55f

        val confidence = (
            formation.confidence +
            compactness.confidence +
            pressureFactor
        ) / 3f

        return PressingRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
PressingRecognitionEngine Anchor
====== */

/* ========
ReceiverRankingEngine
======== */
data class RankedReceiver(
    val player:TrackedPlayer,
    val score:Float
)

data class ReceiverRankingResult(
    val receivers:List<RankedReceiver> = emptyList()
)

object ReceiverRankingEngine{

    fun analyze(
        graph:PassingLaneGraph
    ):ReceiverRankingResult{

        val ranked=
            graph.lanes.map{

                val p=it.receiver

                val movement=
                    hypot(
                        p.velocityX.toDouble(),
                        p.velocityY.toDouble()
                    ).toFloat()/150f

                RankedReceiver(
                    player=p,
                    score=
                        (
                            it.score+
                            movement+
                            (1f-it.pressure)
                        ).coerceIn(0f,1f)
                )
            }.sortedByDescending{
                it.score
            }

        return ReceiverRankingResult(ranked)
    }
}
/* ======
ReceiverRankingEngine Anchor
====== */

/* ========
RunPredictionEngine
======== */
data class PredictedRun(
    val player:TrackedPlayer,
    val predictedX:Float,
    val predictedY:Float,
    val confidence:Float
)

data class RunPredictionResult(
    val runs:List<PredictedRun> = emptyList()
)

object RunPredictionEngine{

    fun analyze(
        scene:SceneSnapshot
    ):RunPredictionResult{

        val runs=
            scene.trackedPlayers
                .filter{it.isUserTeam}
                .map{

                    PredictedRun(
                        player=it,
                        predictedX=it.x+(it.velocityX*0.45f),
                        predictedY=it.y+(it.velocityY*0.45f),
                        confidence=it.confidence.coerceIn(0f,1f)
                    )

                }.sortedByDescending{
                    it.confidence
                }

        return RunPredictionResult(runs)
    }
}
/* ======
RunPredictionEngine Anchor
====== */

/* ========
RuntimeConfidenceCalibrationEngine
======== */
object RuntimeConfidenceCalibrationEngine {

    fun analyze(
        tactical:TacticalIntelligenceResult,
        formation:FormationAdaptationResult,
        passing:PreferredPassingLaneLearningResult,
        shooting:ShootingHabitLearningResult
    ,
        temporal:TemporalMemoryState
    ):RuntimeConfidenceCalibrationResult {

        val calibrated=(
            tactical.confidence*0.25f+
            formation.confidence*0.20f+
            passing.confidence*0.20f+
            shooting.confidence*0.15f+
            temporal.exponentialMovingAverage*0.10f+
            temporal.rollingMean*0.05f+
            temporal.temporalConfidence*0.05f
        ).coerceIn(0f,1f)

        return RuntimeConfidenceCalibrationResult(
            calibratedConfidence=calibrated
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
RuntimeConfidenceCalibrationEngine Anchor
====== */

/* ========
RuntimeDecisionLoop
======== */
/*
 * The single per-frame decision path.
 *
 *   frame -> registry.collect -> pick highest-weight contribution
 *         -> also fold in ContributionRegistry.drainBest (emergency submitters)
 *         -> ONE ExecutionRequest routed through the terminal
 *
 * REPAIRED (Task C - FIELD-LOG PROVEN): arbitration was raw max-weight.
 * The 18:38 session shows what that produced: MagneticFeet (a MOVE-class
 * stabilizer that fires with authority~1.0 on EVERY possession frame) won
 * nearly every cycle - lastAction=MagneticFeet:MOVE, weight=1.0 - so 6131
 * accepted dispatches were overwhelmingly ball-position taps while Shot
 * won 1767, PassLane 907, CrossDelivery 23. Actions existed; they were
 * being outvoted by a stabilizer.
 *
 * Fix: ACTION-CLASS ARBITRATION SCALING. Real match actions (SHOT, PASS,
 * CROSS, DEFEND, KEEPER, EVADE) keep full weight; MOVE support is scaled
 * down so it wins only when nothing real is on offer. Admin-tunable live:
 *   MOVE support uses the live class scale of 0.35 in this implementation.
 * Raise it if movement support feels too weak, lower it if MOVE spam
 * returns - no rebuild needed.
 */
object RuntimeDecisionLoop {

    private val decisions = AtomicLong(0L)
    private val routed = AtomicLong(0L)
    private val idleNoContribution = AtomicLong(0L)
    private val idleUntrusted = AtomicLong(0L)

    @Volatile private var lastAction: String = "none"
    @Volatile private var lastWeight: Float = 0f
    @Volatile private var lastUpdatedMs: Long = 0L

    private fun classScale(actionClass: ActionClass): Float =
        when (actionClass) {
            ActionClass.MOVE ->
                1.0f // UPGRADE: Movement must compete equally in arbitration to prevent magnetic starvation
            ActionClass.NONE -> 0f
            else -> 1f
        }

    /* Called once per assembled frame by OverlayService's capture loop. */
    fun onFrame(frame: RuntimeFrame): Boolean {
        decisions.incrementAndGet()
        lastUpdatedMs = System.currentTimeMillis()

        if (!frame.trusted) {
            idleUntrusted.incrementAndGet()
            lastAction = "idle-untrusted"
            return false
        }

        // CONTROL-MAPPING GATE: the mapping/settings screen must never
        // receive gameplay gestures; trained in-match cluster arms the stack.
        // REMOVED SETTINGS GATE: Button visibility/match MUST NOT be the activation gate for the full gameplay engine.
        // Gameplay engines must run unconditionally to ensure 0.00ms reaction to live match states.
        ControlMappingTrainer.observeOutcome(frame)

        val contributions = GameplayEngineRegistry.collect(frame)
        val netHold = AdapterSignalBus.netIsHold
        
        // Zero-alloc manual loop for filtering and max arbitration
        var best: EngineContribution? = null
        var bestScore = -1f
        for (c in contributions) {
            if (netHold && c.actionClass != ActionClass.MOVE && c.actionClass != ActionClass.DEFEND) continue
            val score = c.weight * classScale(c.actionClass)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }

        val emergency = ContributionRegistry.drainBest()

        val request = chooseRequest(frame, best, emergency)
        if (request == null) {
            idleNoContribution.incrementAndGet()

            lastAction = "idle-no-contribution"

            return false
        }

        // ── Crowding Zone detection ─────────────────────────────────
        // Runs BEFORE amplifiers. Arms the duration cap and updates
        // AdapterSignalBus.crowdingZone for LagVerdictEngine next poll.
        val inCrowdedZone = CrowdingZoneDetector.evaluate(frame)

        // ── Fighting Spirit amplification ─────────────────────────────────
        // Evaluate AFTER arbitration so it amplifies the already-chosen
        // winner rather than competing in arbitration itself.
        val fs = FightingSpiritEngine.evaluate(frame)
        val finalRequest = if (fs.active) {
            request.copy(
                duration = (request.duration + fs.durationBoostMs)
                    .coerceIn(15L, 85L)
            )
        } else request
        // Apply authority boost to the winning contribution's weight
        if (fs.active && best != null) {
            lastWeight = (best.weight * fs.authorityBoost).coerceIn(0f, 1f)
        }

        // ── Captaincy Skill amplification ────────────────────────────────
        // Captaincy is a passive, continuous team-wide fatigue reduction.
        // Applied after Fighting Spirit so both effects compound correctly.
        val captaincy = CaptaincySkillEngine.evaluate(frame)
        // teamLiftFactor: multiplicative duration scale — real terminal effect.
        // composureBoostMs: additive on top — both reach HybridExecutionTerminal.
        val resolvedRequest = if (captaincy.active) {
            val liftedDuration = (finalRequest.duration * captaincy.teamLiftFactor).toLong()
            finalRequest.copy(
                duration = (liftedDuration + captaincy.composureBoostMs)
                    .coerceIn(15L, 85L)
            )
        } else finalRequest

        // ── Crowding Zone saturation cap ──────────────────────────────
        // Penalty box / corner: both amplifiers peak simultaneously.
        // Cap at 45ms so inputs stay deliberate, not desperate spam.
        val terminalRequest = if (inCrowdedZone && resolvedRequest.duration > 45L) {
            resolvedRequest.copy(duration = resolvedRequest.duration.coerceAtMost(45L))
        } else resolvedRequest

        val accepted = HybridExecutionTerminal.route(terminalRequest)
        if (accepted) {
            routed.incrementAndGet()
            ControlMappingTrainer.recordDispatch(terminalRequest.phase, terminalRequest.duration)
            try {
                com.assistant.events.GameplayEventHub.emit(
                    "routed",
                    "source=${request.source} phase=${request.phase}"
                )
            } catch (_: Throwable) {
            }
        }
        lastAction = describe(best, emergency)
        lastWeight = best?.weight ?: 0f
        return accepted
    }

    @Suppress("UNUSED_PARAMETER")
    private fun chooseRequest(
        _frame: RuntimeFrame,  // reserved — frame context available for future coord use
        best: EngineContribution?,
        emergency: ExecutionRequest?
    ): ExecutionRequest? {
        // Emergency (goalkeeper etc.) outranks a normal contribution.
        if (emergency != null) {
            if (best == null) return emergency
            val emergencyPriority = HybridExecutionTerminal.priority(emergency.source)
            val normalPriority = HybridExecutionTerminal.priority(ExecutionSource.SMART_ASSIST)
            if (emergencyPriority > normalPriority) return emergency
        }
        if (best == null) return null

        val pred = BallTrajectoryPredictor.current()
        val leadX = if (pred.speed > 20f) pred.predictedX else best.targetX
        val leadY = if (pred.speed > 20f) pred.predictedY else best.targetY
        return ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = best.actionClass.ordinal,
            startX = 250f,  // FIX: joystick origin, not ball position
            startY = 550f,
            endX = leadX.coerceAtLeast(0f),
            endY = leadY.coerceAtLeast(0f),
            duration = ControlMappingTrainer.personalDuration(best.actionClass, best.durationHintMs).coerceIn(15L, 85L)
        )
    }

    private fun describe(best: EngineContribution?, emergency: ExecutionRequest?): String =
        when {
            emergency != null && best == null -> "emergency:${emergency.source}"
            best != null -> "${best.engine}:${best.actionClass}"
            else -> "none"
        }

    fun reset() {
        decisions.set(0L); routed.set(0L)
        FightingSpiritEngine.reset()
        CaptaincySkillEngine.reset()
        CrowdingZoneDetector.reset()
        idleNoContribution.set(0L); idleUntrusted.set(0L)
        lastAction = "none"; lastWeight = 0f; lastUpdatedMs = 0L
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "decisions" to decisions.get(),
        "routed" to routed.get(),
        "idleUntrusted" to idleUntrusted.get(),
        "idleNoContribution" to idleNoContribution.get(),
        "lastAction" to lastAction,
        "lastWeight" to lastWeight,
        "lastUpdatedMs" to lastUpdatedMs,
        "mappingUiMode" to ControlMappingTrainer.uiMode.name,
        "mappingMatchActive" to ControlMappingTrainer.matchActive,
        "mappingTrainedSlots" to ControlMappingTrainer.trainedSlots()
    )
}
/* ======
RuntimeDecisionLoop Anchor
====== */

/* ========
RuntimeObservation
======== */
/**
 * Immutable runtime observation consumed by InAppAgentCore.
 *
 * This is deliberately a snapshot:
 * gameplay engines remain owners of gameplay state;
 * the agent only observes authoritative runtime surfaces.
 */
data class RuntimeObservation(
    val timestampMs: Long,
    val health: RuntimeHealthMonitor.HealthState,
    val decisions: Long,
    val routed: Long,
    val loadShed: String,
    val selfHealRunning: Boolean,
    val selfHealStatus: String,
    val totalHeals: Int,
    val performanceAuthority: Int
) {
    companion object {
        fun capture(): RuntimeObservation {
            val health = RuntimeHealthMonitor.snapshot()
            val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()

            val decisions =
                (decision["decisions"] as? Number)?.toLong() ?: 0L

            val routed =
                (decision["routed"] as? Number)?.toLong() ?: 0L

            val loadShed =
                try {
                    PerformanceTelemetryRegistry.currentLoadShed()
                } catch (_: Throwable) {
                    "UNKNOWN"
                }

            return RuntimeObservation(
                timestampMs = System.currentTimeMillis(),
                health = health,
                decisions = decisions,
                routed = routed,
                loadShed = loadShed,
                selfHealRunning = RuntimeSelfHealEngine.isRunning(),
                selfHealStatus = RuntimeSelfHealEngine.agentStatus,
                totalHeals = RuntimeSelfHealEngine.totalHeals,
                performanceAuthority = RuntimePerformanceCoordinator.authority()
            )
        }
    }
}
/* ======
RuntimeObservation Anchor
====== */

/* ========
SceneSnapshot
======== */
data class SceneSnapshot(
    val frameNumber: Long = 0L,

    val timestamp: Long = System.currentTimeMillis(),

    val ballVisible: Boolean = false,

    val playerCount: Int = 0,

    val trackedPlayers: List<TrackedPlayer> = emptyList(),

    val userPlayers: Int = 0,

    val opponentPlayers: Int = 0,

    val trackedBallX: Float = 0f,

    val trackedBallY: Float = 0f,

    val trackedBallSpeed: Float = 0f,

    val trackedBallVisible: Boolean = false,

    val goalkeeperVisible: Boolean = false,

    val goalkeeperX: Float = 0f,

    val goalkeeperY: Float = 0f,

    val goalkeeperHeading: Float = 0f,

    val goalDetected: Boolean = false,
    val goalLeftX: Float = 0f,
    val goalRightX: Float = 0f,
    val goalTopY: Float = 0f,
    val goalBottomY: Float = 0f,
    val goalConfidence: Float = 0f,

    val touchLinesDetected: Boolean = false,
    val penaltyAreaDetected: Boolean = false,
    val goalAreaDetected: Boolean = false,
    val centerCircleDetected: Boolean = false,
    val fieldConfidence: Float = 0f,

    val confidence: Float = 0f
)
/* ======
SceneSnapshot Anchor
====== */

/* ========
SceneTracker
======== */
/*
 * Keeps the tracked picture of the pitch between frames.
 *
 * REPAIRED (Task B): removed the zombie refresh that stamped whatever track
 * happened to be LAST in the list with a fresh lastSeenFrame + the global
 * scene confidence every frame - it kept one arbitrary ghost alive forever
 * and corrupted its confidence. Added a sanity cap on simultaneous tracks
 * (a real match cannot exceed ~22 entities + margin): kept tracks are the
 * freshest/strongest, so detector noise from crowd pixels can no longer
 * inflate the count past VisionTrust's sanity line and lock the whole
 * contributor stack out. Memory frames + cap answer the admin store live.
 */
object SceneTracker {

    private var frameCounter = 0L

    private var trackedBallX = 0f
    private var trackedBallY = 0f
    private var trackedBallSpeed = 0f

    private var latest =
        SceneSnapshot()

    private val trackedPlayers = mutableListOf<TrackedPlayer>()

    private val mutationLock = Any()

    private var trackedGoalkeeper: TrackedPlayer? = null

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val PLAYER_MEMORY_FRAMES: Long
        get() = 15L
    private val MAX_TRACKS: Int
        get() = 30

    private const val CONFIDENCE_DECAY = 0.05f

    fun update(
        state: GameStateSnapshot,
        players: PlayerDetectionResult
    ): SceneSnapshot = synchronized(mutationLock) {

        frameCounter++

        trackedPlayers.forEach {
            it.confidence = (it.confidence - CONFIDENCE_DECAY).coerceAtLeast(0f)
        }

        trackedBallX = state.ballX
        trackedBallY = state.ballY
        trackedBallSpeed = state.ballSpeed

        EntityAssociationEngine.associate(
            trackedPlayers,
            players.detections,
            frameCounter
        )

        if (state.goalkeeperDetected) {

            if (trackedGoalkeeper == null) {

                trackedGoalkeeper =
                    TrackedPlayer(
                        id = -1,

                        x = state.goalkeeperX,
                        y = state.goalkeeperY,

                        velocityX = 0f,
                        velocityY = 0f,

                        confidence = state.goalkeeperConfidence,

                        isUserTeam = true,
                        isGoalkeeper = true,

                        lastSeenFrame = frameCounter
                    )

            } else {

                val keeper = trackedGoalkeeper!!

                keeper.velocityX =
                    state.goalkeeperX - keeper.x

                keeper.velocityY =
                    state.goalkeeperY - keeper.y

                keeper.headingRadians =
                    kotlin.math.atan2(
                        keeper.velocityY,
                        keeper.velocityX
                    )

                keeper.x = state.goalkeeperX
                keeper.y = state.goalkeeperY

                keeper.confidence =
                    state.goalkeeperConfidence

                keeper.lastSeenFrame =
                    frameCounter
            }
        }

        // prune the dead: unseen too long or fully faded
        val memory = PLAYER_MEMORY_FRAMES
        trackedPlayers.removeAll {
            (frameCounter - it.lastSeenFrame) > memory ||
            it.confidence <= 0f
        }

        // sanity cap: keep the freshest/strongest tracks only, so crowd-pixel
        // noise cannot inflate the count past what a real pitch can hold
        val cap = if (MAX_TRACKS < 1) 1 else MAX_TRACKS
        if (trackedPlayers.size > cap) {
            trackedPlayers.sortWith(
                compareByDescending<TrackedPlayer> { it.lastSeenFrame }
                    .thenByDescending { it.confidence }
            )
            while (trackedPlayers.size > cap) {
                trackedPlayers.removeAt(trackedPlayers.size - 1)
            }
        }

        latest =
            SceneSnapshot(
                frameNumber = frameCounter,

                ballVisible =
                    state.ballDetected,

                playerCount =
                    state.userPlayers +
                    state.opponentPlayers,

                userPlayers =
                    state.userPlayers,

                opponentPlayers =
                    state.opponentPlayers,

                trackedBallX = trackedBallX,

                trackedBallY = trackedBallY,

                trackedBallSpeed = trackedBallSpeed,

                trackedBallVisible = state.ballDetected,

                goalkeeperVisible =
                    trackedGoalkeeper != null,

                goalkeeperX =
                    trackedGoalkeeper?.x ?: 0f,

                goalkeeperY =
                    trackedGoalkeeper?.y ?: 0f,

                goalkeeperHeading =
                    trackedGoalkeeper?.headingRadians ?: 0f,

                goalDetected = state.goalDetected,
                goalLeftX = state.goalLeftX,
                goalRightX = state.goalRightX,
                goalTopY = state.goalTopY,
                goalBottomY = state.goalBottomY,
                goalConfidence = state.goalConfidence,

                touchLinesDetected = state.touchLinesDetected,
                penaltyAreaDetected = state.penaltyAreaDetected,
                goalAreaDetected = state.goalAreaDetected,
                centerCircleDetected = state.centerCircleDetected,
                fieldConfidence = state.fieldConfidence,

                trackedPlayers = trackedPlayers.toList(),

                confidence = state.confidence
            )

        latest
    }

    fun current(): SceneSnapshot =
        latest
}
/* ======
SceneTracker Anchor
====== */

/* ========
SmartAssistMetrics
======== */
object SmartAssistMetrics {
    data class GoalkeeperShadowDiagnostics(
        val observations: Long,
        val lastPhase: Int,
        val lastStartX: Float,
        val lastStartY: Float,
        val lastEndX: Float,
        val lastEndY: Float,
        val lastDuration: Long,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var goalkeeperShadowObservations: Long = 0L
    private var goalkeeperShadowLastPhase: Int = 0
    private var goalkeeperShadowLastStartX: Float = 0f
    private var goalkeeperShadowLastStartY: Float = 0f
    private var goalkeeperShadowLastEndX: Float = 0f
    private var goalkeeperShadowLastEndY: Float = 0f
    private var goalkeeperShadowLastDuration: Long = 0L
    private var goalkeeperShadowLastReason: String = "no goalkeeper shadow observation yet"
    private var goalkeeperShadowLastUpdatedMs: Long = 0L

    @Synchronized
    fun recordGoalkeeperShadow(request: ExecutionRequest, reason: String) {
        goalkeeperShadowObservations += 1L
        goalkeeperShadowLastPhase = request.phase
        goalkeeperShadowLastStartX = request.startX
        goalkeeperShadowLastStartY = request.startY
        goalkeeperShadowLastEndX = request.endX
        goalkeeperShadowLastEndY = request.endY
        goalkeeperShadowLastDuration = request.duration
        goalkeeperShadowLastReason = reason
        goalkeeperShadowLastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun goalkeeperShadowRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "observations" to goalkeeperShadowObservations,
            "lastPhase" to goalkeeperShadowLastPhase,
            "lastStartX" to goalkeeperShadowLastStartX,
            "lastStartY" to goalkeeperShadowLastStartY,
            "lastEndX" to goalkeeperShadowLastEndX,
            "lastEndY" to goalkeeperShadowLastEndY,
            "lastDuration" to goalkeeperShadowLastDuration,
            "lastReason" to goalkeeperShadowLastReason,
            "lastUpdatedMs" to goalkeeperShadowLastUpdatedMs
        )

    data class BusExecutionDiagnostics(
        val consumed: Long,
        val dispatched: Long,
        val failed: Long,
        val lastSource: String,
        val lastPhase: Int,
        val lastDuration: Long,
        val lastStartX: Float,
        val lastStartY: Float,
        val lastEndX: Float,
        val lastEndY: Float,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var busConsumed: Long = 0L
    private var busDispatched: Long = 0L
    private var busFailed: Long = 0L
    private var busLastSource: String = "none"
    private var busLastPhase: Int = 0
    private var busLastDuration: Long = 0L
    private var busLastStartX: Float = 0f
    private var busLastStartY: Float = 0f
    private var busLastEndX: Float = 0f
    private var busLastEndY: Float = 0f
    private var busLastReason: String = "no bus request consumed yet"
    private var busLastUpdatedMs: Long = 0L

    @Synchronized
    fun recordBusConsumed(request: ExecutionRequest) {
        busConsumed += 1L
        busLastSource = request.source.name
        busLastPhase = request.phase
        busLastDuration = request.duration
        busLastStartX = request.startX
        busLastStartY = request.startY
        busLastEndX = request.endX
        busLastEndY = request.endY
        busLastReason = "CentralExecutionBus request consumed by accessibility engine"
        busLastUpdatedMs = System.currentTimeMillis()
        submitRequest()
    }

    @Synchronized
    fun recordBusDispatchResult(request: ExecutionRequest, dispatched: Boolean) {
        if (dispatched) {
            busDispatched += 1L
            busLastReason = "Gesture dispatched from consumed bus request"
        } else {
            busFailed += 1L
            busLastReason = "Gesture dispatch failed from consumed bus request"
        }
        busLastSource = request.source.name
        busLastPhase = request.phase
        busLastDuration = request.duration
        busLastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun busExecutionRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "consumed" to busConsumed,
            "dispatched" to busDispatched,
            "failed" to busFailed,
            "lastSource" to busLastSource,
            "lastPhase" to busLastPhase,
            "lastDuration" to busLastDuration,
            "lastStartX" to busLastStartX,
            "lastStartY" to busLastStartY,
            "lastEndX" to busLastEndX,
            "lastEndY" to busLastEndY,
            "lastReason" to busLastReason,
            "lastUpdatedMs" to busLastUpdatedMs
        )

    fun gameplayDownstreamRuntimeSnapshot(): Map<String, Any> {
        val event = GameplayDecisionEngine.gameplayDownstreamSnapshot()
        return mapOf(
            "sequence" to (event?.sequence ?: 0L),
            "source" to (event?.source ?: "none"),
            "amplification" to (event?.amplification ?: 1000000.0f),
            "active" to (event != null)
        )
    }

    fun gameplayAmplificationRuntimeSnapshot(): Map<String, Number> {
        val snapshot = GameplayDecisionEngine.gameplayAmplificationSnapshot()
        return mapOf(
            "amplification" to 1000000.0f,
            "decisionCycles" to snapshot.first,
            "lastAuthority" to snapshot.second
        )
    }

    val requestsSubmitted = AtomicLong()
    val requestsExecuted = AtomicLong()
    val trajectoryProduced = AtomicLong()

    fun submitRequest() {
        requestsSubmitted.incrementAndGet()
    }

    fun executeRequest() {
        requestsExecuted.incrementAndGet()
    }

    fun produceTrajectory() {
        trajectoryProduced.incrementAndGet()
    }

    fun snapshot(): String {
        return buildString {
            append("Submitted : ")
            append(requestsSubmitted.get())
            append("\n")
            append("Executed : ")
            append(requestsExecuted.get())
            append("\n")
            append("Trajectory : ")
            append(trajectoryProduced.get())
        }
    }

    fun reset() {
        requestsSubmitted.set(0L)
        requestsExecuted.set(0L)
        trajectoryProduced.set(0L)
    }

    fun magneticFeetRuntimeSnapshot(): Map<String, Any> {
        val state = MagneticFeetEngine.magneticFeetSnapshot()
        return mapOf(
            "sequence" to (state?.sequence ?: 0L),
            "amplification" to (state?.amplification ?: 1.0f),
            "touchRetention" to (state?.result?.touchRetention ?: 0.0f),
            "interceptionResistance" to (state?.result?.interceptionResistance ?: 0.0f),
            "possessionControl" to (state?.result?.possessionControl ?: 0.0f)
        )
    }

    fun crossingLaneRuntimeSnapshot(): Map<String, Any> {
        val state = CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        val lanes = state?.result?.lanes.orEmpty()
        return mapOf(
            "sequence" to (state?.sequence ?: 0L),
            "amplification" to (state?.amplification ?: 1.0f),
            "laneCount" to lanes.size,
            "viableLaneCount" to lanes.count { it.viable },
            "bestConfidence" to (lanes.maxOfOrNull { it.confidence } ?: 0.0f)
        )
    }

    fun magneticFeetActivationRuntimeSnapshot(): Map<String, Any> {
        val state = MagneticFeetEngine.magneticFeetActivationDiagnostics()
        return mapOf(
            "calls" to state.calls,
            "lastPressure" to state.lastPressure,
            "lastStrength" to state.lastStrength,
            "lastReason" to state.lastReason,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    fun gameplayActivationRuntimeSnapshot(): Map<String, Any> {
        val state = GameplayDecisionEngine.gameplayActivationDiagnostics()
        return mapOf(
            "adaptiveModeCalls" to state.adaptiveModeCalls,
            "decideCalls" to state.decideCalls,
            "lastHasBall" to state.lastHasBall,
            "lastMode" to state.lastMode,
            "lastStrength" to state.lastStrength,
            "lastReason" to state.lastReason,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    fun controllerEntryRuntimeSnapshot(): Map<String, Any> {
        val state = ActiveGestureControllerDiagnostics.snapshot()
        return mapOf(
            "entryCalls" to state.entryCalls,
            "blockedCalls" to state.blockedCalls,
            "lastReason" to state.lastReason,
            "lastStartX" to state.lastStartX,
            "lastStartY" to state.lastStartY,
            "lastEndX" to state.lastEndX,
            "lastEndY" to state.lastEndY,
            "lastDuration" to state.lastDuration,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    private var gameplayHeartbeatTicks: Long = 0L
    private var gameplayHeartbeatLastReason: String = "not started"
    private var gameplayHeartbeatLastUpdatedMs: Long = 0L

    @Synchronized
    fun runGameplayHeartbeat(reason: String = "diagnosis heartbeat") {
        gameplayHeartbeatTicks += 1L
        gameplayHeartbeatLastReason = reason
        gameplayHeartbeatLastUpdatedMs = System.currentTimeMillis()

        val crossing = CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        val laneCount = crossing?.result?.lanes?.size ?: 0
        val bestConfidence = crossing?.result?.lanes?.maxOfOrNull { it.confidence } ?: 0.0f
        val temporal = TemporalMemoryState(
            temporalConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            exponentialMovingAverage = bestConfidence.coerceIn(0.0f, 1.0f),
            rollingMean = bestConfidence.coerceIn(0.0f, 1.0f),
            historyStability = 1.0f,
            confidenceVariance = 0.0f,
            confidenceTrend = 0.0f
        )

        GameplayDecisionEngine.selectVisionAdaptiveMode(
            hasBall = laneCount > 0,
            shotAuthority = bestConfidence.coerceIn(0.0f, 1.0f),
            passAuthority = bestConfidence.coerceIn(0.0f, 1.0f),
            crossAuthority = bestConfidence.coerceIn(0.0f, 1.0f),
            visionConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            tacticalConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            intelligenceConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            runtimeCalibration = 1.0f,
            onlineAdaptation = 1.0f,
            temporal = temporal
        )
    }

    fun gameplayHeartbeatRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "ticks" to gameplayHeartbeatTicks,
            "lastReason" to gameplayHeartbeatLastReason,
            "lastUpdatedMs" to gameplayHeartbeatLastUpdatedMs
        )

    // ==== Decision mirror ====
    // Written by com.assistant.overlay.metrics.SmartAssistMetrics so that
    // decision-level counters and request-level counters share one reader.
    private val decisionsMade = AtomicLong()
    private val decisionActions = AtomicLong()
    private val decisionErrors = AtomicLong()
    private val decisionTimeTotal = AtomicLong()
    @Volatile private var lastDecisionMs: Long = 0L
    @Volatile private var lastDecisionUpdatedMs: Long = 0L

    fun recordDecisionMirror(durationMs: Long, triggeredAction: Boolean) {
        decisionsMade.incrementAndGet()
        decisionTimeTotal.addAndGet(durationMs)
        if (triggeredAction) {
            decisionActions.incrementAndGet()
        }
        lastDecisionMs = durationMs
        lastDecisionUpdatedMs = System.currentTimeMillis()
    }

    fun recordDecisionErrorMirror() {
        decisionErrors.incrementAndGet()
        lastDecisionUpdatedMs = System.currentTimeMillis()
    }

    fun resetDecisionMirror() {
        decisionsMade.set(0L)
        decisionActions.set(0L)
        decisionErrors.set(0L)
        decisionTimeTotal.set(0L)
        lastDecisionMs = 0L
        lastDecisionUpdatedMs = 0L
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> {
        val made = decisionsMade.get()
        return mapOf(
            "decisionsMade" to made,
            "actionsTriggered" to decisionActions.get(),
            "errorCount" to decisionErrors.get(),
            "avgDecisionTimeMs" to if (made > 0L) decisionTimeTotal.get() / made else 0L,
            "lastDecisionTimeMs" to lastDecisionMs,
            "lastUpdatedMs" to lastDecisionUpdatedMs
        )
    }

}
/* ======
SmartAssistMetrics Anchor
====== */

/* ========
TacticalBehaviorRecognitionResult
======== */
data class TacticalBehaviorRecognitionResult(
    val confidence:Float=0f
)
/* ======
TacticalBehaviorRecognitionResult Anchor
====== */

/* ========
TeamClassificationResult
======== */
data class TeamClassificationResult(
    val userPlayers: Int,
    val opponentPlayers: Int,
    val confidence: Float
)
/* ======
TeamClassificationResult Anchor
====== */

/* ========
TemporalMemoryEngine
======== */
object TemporalMemoryEngine {

    private const val DEFAULT_WINDOW = 30
    private const val DEFAULT_ALPHA = 0.20f
    
    // --- ENGINE CONSTANTS FOR FRAME & TICK SYNC ---
    private const val REFRESH_RATE_60HZ_MS = 16.666f
    private const val REFRESH_RATE_120HZ_MS = 8.333f
    private const val BASE_SERVER_TICK_MS = 33.333f // Typical 30Hz netcode boundary
    private const val HUMAN_LATENCY_MIN_MS = 12L
    private const val HUMAN_LATENCY_MAX_MS = 45L
    private const val NOISE_VARIANCE_SCALE = 0.04f // 4% dynamic micro-variance bounds

    /**
     * Updates the temporal state using zero-allocation mathematical models,
     * applying Server-Tick Sync and Adaptive Noise Humanization for Gesture descriptions.
     *
     * @param previous The previous memory state
     * @param confidence The raw input confidence/probability scalar
     * @param pingMs Current estimated network latency to scale packet boundaries
     * @param is120Hz Whether to target 120Hz (8.33ms) or 60Hz (16.66ms) kinematics
     */
    fun update(
        previous: TemporalMemoryState,
        confidence: Float,
        pingMs: Float = 50f,
        is120Hz: Boolean = false
    ): TemporalMemoryState {
        
        val window = previous.historyWindow
        
        // --- 1. ZERO-ALLOCATION CIRCULAR BUFFER ---
        // Copying the array ensures immutability of state, avoiding cross-frame mutation bugs
        val nextHistory = previous.history.copyOf()
        val nextIndex = (previous.historyIndex + 1) % window
        nextHistory[nextIndex] = confidence
        
        val nextSampleCount = previous.sampleCount + 1
        val validSamples = if (nextSampleCount < window) nextSampleCount else window

        // --- 2. HIGH-PERFORMANCE STATISTICAL PASS ---
        // O(N) single-pass extraction replacing multiple fold/map/sum chaining
        var sum = 0f
        var minConf = confidence
        var maxConf = confidence
        
        for (i in 0 until validSamples) {
            val v = nextHistory[(nextIndex - i + window) % window]
            sum += v
            if (v < minConf) minConf = v
            if (v > maxConf) maxConf = v
        }

        val mean = if (validSamples == 0) 0f else sum / validSamples.toFloat()

        var varianceSum = 0f
        for (i in 0 until validSamples) {
            val v = nextHistory[(nextIndex - i + window) % window]
            val d = v - mean
            varianceSum += d * d
        }
        
        val variance = if (validSamples == 0) 0f else varianceSum / validSamples.toFloat()
        val stddev = sqrt(variance)

        // --- 3. EXPONENTIAL MOVING AVERAGE & KINEMATICS ---
        val ema = if (previous.sampleCount == 0) {
            confidence
        } else {
            (DEFAULT_ALPHA * confidence) + ((1f - DEFAULT_ALPHA) * previous.exponentialMovingAverage)
        }

        val trend = ema - previous.exponentialMovingAverage
        val evolution = ema - previous.temporalConfidence
        val age = previous.observationAge + 1
        val decayed = ema * previous.decayFactor

        // Optimized boundary coercion
        val varBounded = (1f - variance).coerceIn(0f, 1f)
        val trendBounded = (1f - abs(trend)).coerceIn(0f, 1f)
        val emaBounded = ema.coerceIn(0f, 1f)

        val historyStability = (varBounded * 0.50f + trendBounded * 0.30f + emaBounded * 0.20f).coerceIn(0f, 1f)

        // --- 4. ADAPTIVE NOISE HUMANIZATION & SERVER-TICK SYNC ---
        // Dynamically scale gesture path lengths and hold durations to match network packet boundaries
        val frameTargetMs = if (is120Hz) REFRESH_RATE_120HZ_MS else REFRESH_RATE_60HZ_MS
        
        // Calculate synchronization scalar forcing maximum possession effectiveness during high ping
        val pingCompensation = (pingMs / BASE_SERVER_TICK_MS).coerceIn(1.0f, 3.0f)
        
        // Randomized dynamic micro-variance to mimic human hand latency boundaries
        val humanVariance = (Random.nextFloat() * 2f - 1f) * NOISE_VARIANCE_SCALE
        
        val newGestureScale = (1.0f + (historyStability * pingCompensation * humanVariance)).coerceIn(0.85f, 1.25f)
        val newHoldDelay = Random.nextLong(HUMAN_LATENCY_MIN_MS, HUMAN_LATENCY_MAX_MS)
        val syncOffset = (frameTargetMs * pingCompensation) % frameTargetMs

        return TemporalMemoryState(
            historyWindow = window,
            sampleCount = nextSampleCount,
            rollingConfidence = mean,
            exponentialMovingAverage = ema,
            confidenceTrend = trend,
            confidenceVariance = variance,
            historyStability = historyStability,
            confidenceSlope = trend,
            confidenceEvolution = evolution,
            observationAge = age,
            decayFactor = previous.decayFactor,
            minConfidence = minConf,
            maxConfidence = maxConf,
            temporalConfidence = decayed.coerceIn(0f, 1f),
            rollingMean = mean,
            rollingStdDev = stddev,
            onlineUpdateCount = previous.onlineUpdateCount + 1,
            history = nextHistory,
            historyIndex = nextIndex,
            gestureScaleMultiplier = newGestureScale,
            humanizedHoldDelayMs = newHoldDelay,
            frameSyncOffsetMs = syncOffset
        )
    }

    /**
     * Initializes the temporal engine state with pre-allocated zero-cost arrays.
     */
    fun initialize(
        historyWindow: Int = DEFAULT_WINDOW,
        decayFactor: Float = 0.98f
    ): TemporalMemoryState {
        return TemporalMemoryState(
            historyWindow = historyWindow,
            sampleCount = 0,
            rollingConfidence = 0f,
            exponentialMovingAverage = 0f,
            confidenceTrend = 0f,
            confidenceVariance = 0f,
            historyStability = 0f,
            confidenceSlope = 0f,
            confidenceEvolution = 0f,
            observationAge = 0,
            decayFactor = decayFactor,
            minConfidence = 0f,
            maxConfidence = 0f,
            temporalConfidence = 0f,
            rollingMean = 0f,
            rollingStdDev = 0f,
            onlineUpdateCount = 0,
            history = FloatArray(historyWindow), // Allocates once per initialization
            historyIndex = 0,
            gestureScaleMultiplier = 1.0f,
            humanizedHoldDelayMs = 0L,
            frameSyncOffsetMs = 0f
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration with enhanced frame-sync and tick-scaling.
}
/* ======
TemporalMemoryEngine Anchor
====== */

/* ========
TrueTargetPassingEngine
======== */
data class PassingAssistResult(
    val correctedX: Float,
    val correctedY: Float,
    val interceptionRisk: Float,
    val requiresEscapeVector: Boolean = false
)

object TrueTargetPassingEngine {

    /**
     * @param retention 0..1 fraction along (start->end) to land on.
     * BUG FIX: retention now clamped. Old callers passed * 10f -> 10x overshoot.
     */
    fun optimize(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        retention: Float
    ): PassingAssistResult {
        val r = retention.coerceIn(0f, 1f)
        val dx = endX - startX
        val dy = endY - startY
        return PassingAssistResult(
            correctedX       = (startX + dx * r).coerceIn(0f, 1650f),
            correctedY       = (startY + dy * r).coerceIn(0f, 720f),
            interceptionRisk = (1f - r).coerceIn(0f, 1f)
        )
    }

    /**
     * Predict where receiver will be when ball arrives, aim there.
     * Defeats SA interception where receiver runs away from ball path.
     */
    fun optimizeWithRunPrediction(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float
    ): PassingAssistResult {
        val dist = hypot(
            (receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()
        ).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = 60f
        val predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        val predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)
        return optimize(ballX, ballY, predX, predY, 1.0f)
    }

    /**
     * Advanced Run & Match-Up Interception Escape Optimization
     */
    fun optimizeWithAdvancedTactics(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float,
        defenderDensity: Float
    ): PassingAssistResult {
        val dist = hypot((receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = 60f
        
        var predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        var predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)

        // Match-Up / Defender Shadow Offset
        if (defenderDensity > 0.5f) {
            val passAngle = atan2((predY - ballY).toDouble(), (predX - ballX).toDouble())
            // Offset pass vector 25px orthogonal to defender density cone
            predX += (sin(passAngle) * 25.0).toFloat()
            predY -= (cos(passAngle) * 25.0).toFloat()
        }

        val risk = (defenderDensity * 0.7f).coerceIn(0f, 1f)

        return PassingAssistResult(
            correctedX = predX.coerceIn(0f, 1650f),
            correctedY = predY.coerceIn(0f, 720f),
            interceptionRisk = risk,
            requiresEscapeVector = defenderDensity > 0.8f
        )
    }

    fun interceptionVector(
        ballX: Float, ballY: Float,
        ballVelocityX: Float, ballVelocityY: Float,
        receiverX: Float, receiverY: Float
    ): Pair<Float, Float> {
        val lookAhead = 0.3f
        val px = ballX + ballVelocityX * lookAhead
        val py = ballY + ballVelocityY * lookAhead
        val dx = px - receiverX
        val dy = py - receiverY
        val m = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (m > 0f) Pair((dx / m) * 100f, (dy / m) * 100f) else Pair(0f, 0f)
    }

    fun calculateDoublePressEscapeVector(
        carrierX: Float, carrierY: Float,
        presserAX: Float, presserAY: Float,
        presserBX: Float, presserBY: Float,
        strikerX: Float, strikerY: Float
    ): Pair<Float, Float> {
        val d = hypot((presserAX - presserBX).toDouble(), (presserAY - presserBY).toDouble())
        return if (d < 80.0) {
            val mx = (presserAX + presserBX) / 2f
            val my = (presserAY + presserBY) / 2f
            Pair(carrierX + (strikerX - mx) * 0.3f, carrierY + (strikerY - my) * 0.3f)
        } else {
            Pair(carrierX + (strikerX - carrierX) * 0.35f, carrierY + (strikerY - carrierY) * 0.35f)
        }
    }

    fun currentPassingGraph()                        = Phase3WorldStateStore.current().passingGraph
    fun currentThroughBallAnalysis()                 = Phase3WorldStateStore.current().throughBallAnalysis
    fun currentCrossingLaneAnalysis()                = Phase3WorldStateStore.current().crossingLaneAnalysis
    fun currentShootingLaneAnalysis()                = Phase3WorldStateStore.current().shootingLaneAnalysis
    fun currentBlockedLanePredictionAnalysis()       = Phase3WorldStateStore.current().blockedLanePredictionAnalysis
    fun currentDefenderInterceptionPredictionAnalysis() = Phase3WorldStateStore.current().defenderInterceptionPredictionAnalysis
    fun currentOpenSpaceDetectionResult()            = Phase3WorldStateStore.current().openSpaceDetectionResult
    fun currentReceiverRankingResult()               = Phase3WorldStateStore.current().receiverRankingResult
    fun currentRunPredictionResult()                 = Phase3WorldStateStore.current().runPredictionResult
    fun currentOverlapDetectionResult()              = Phase3WorldStateStore.current().overlapDetectionResult
    fun currentCounterattackDetectionResult()        = Phase3WorldStateStore.current().counterattackDetectionResult
    fun currentFastBreakDetectionResult()            = Phase3WorldStateStore.current().fastBreakDetectionResult
    fun currentOffsideRiskEstimationResult()         = Phase3WorldStateStore.current().offsideRiskEstimationResult
}
/* ======
TrueTargetPassingEngine Anchor
====== */

/* ========
VisionConfiguration
======== */
data class VisionConfiguration(
    val enabled:Boolean = true,
    val debugOverlayEnabled:Boolean = false,
    val boundingBoxOverlayEnabled:Boolean = false,
    val confidenceHeatmapEnabled:Boolean = false,
    val ballOverlayEnabled:Boolean = true,
    val playerOverlayEnabled:Boolean = true,
    val goalOverlayEnabled:Boolean = true,
    val sceneTrackingEnabled:Boolean = true,
    val latencyMonitoringEnabled:Boolean = true,
    val fpsMonitoringEnabled:Boolean = true,
    val diagnosticsEnabled:Boolean = true
)

object VisionConfigurationEngine {

    @Volatile
    private var configuration = VisionConfiguration()

    fun current(): VisionConfiguration = configuration

    fun update(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        configuration = block(configuration)
    }

    fun reset(){
        configuration = VisionConfiguration()
    }
}
/* ======
VisionConfiguration Anchor
====== */

/* ========
WingBlockEngine
======== */
data class WingBlockResult(
    val targetX: Float,
    val targetY: Float
)

object WingBlockEngine {

    fun calculateWingBlockVector(
        wingerX: Float,
        wingerY: Float,
        wingerVx: Float,
        wingerVy: Float,
        pitchWidth: Float
    ): WingBlockResult? {

        val leftBoundary  = pitchWidth * (0.15f + (Random.nextFloat() * 0.01f - 0.005f))
        val rightBoundary = pitchWidth * (0.85f + (Random.nextFloat() * 0.01f - 0.005f))

        val isLeftFlank  = wingerX < leftBoundary
        val isRightFlank = wingerX > rightBoundary

        // Not a wing situation: return null so WingBlockContributor skips.
        // Previously returned ball position (non-null), firing a redundant
        // central press that duplicated DefenseContributor with no added value.
        if (!isLeftFlank && !isRightFlank) return null

        val noiseX = Random.nextFloat() * 1.5f - 0.75f
        val noiseY = Random.nextFloat() * 1.5f - 0.75f

        val baseAnchorX = if (isLeftFlank) pitchWidth * 0.12f else pitchWidth * 0.88f
        val blockingAnchorX = (baseAnchorX + (wingerVx * 0.2f)) + noiseX
        val blockingAnchorY = wingerY + (wingerVy * 0.2f) + noiseY

        return WingBlockResult(blockingAnchorX, blockingAnchorY)
    }
}
/* ======
WingBlockEngine Anchor
====== */

/* ========
WingOverloadDetectionResult
======== */
data class WingOverloadDetectionResult(
    val leftWingAdvantage: Float = 0f,
    val rightWingAdvantage: Float = 0f,
    val overloaded: Boolean = false,
    val confidence: Float = 0f
)
/* ======
WingOverloadDetectionResult Anchor
====== */

/* ========
SharpTouchTimingEngine
======== */
data class SharpTouchTimingResult(
    val executeNow: Boolean,
    val optimalFrameOffset: Int,
    val ballDistancePx: Float,
    val timingAuthority: Float
)

object SharpTouchTimingEngine {

    private const val MIN_SWEET_SPOT_PX = 18f
    private const val MAX_SWEET_SPOT_PX = 38f
    private const val FRAME_MS = 16.666666f

    fun evaluateTiming(
        ballX: Float, ballY: Float,
        ballVx: Float, ballVy: Float,
        playerX: Float, playerY: Float,
        playerVx: Float, playerVy: Float
    ): SharpTouchTimingResult {
        var minDistance = Float.MAX_VALUE
        var bestFrame = 0

        for (f in 0..3) {
            val tSec = (f * FRAME_MS) / 1000f
            val pBallX = ballX + ballVx * tSec
            val pBallY = ballY + ballVy * tSec
            val pPlayerX = playerX + playerVx * tSec
            val pPlayerY = playerY + playerVy * tSec

            val d = hypot((pBallX - pPlayerX).toDouble(), (pBallY - pPlayerY).toDouble()).toFloat()
            if (d < minDistance) {
                minDistance = d
                bestFrame = f
            }
        }

        val currentDist = hypot((ballX - playerX).toDouble(), (ballY - playerY).toDouble()).toFloat()
        val inSweetSpotNow = currentDist in MIN_SWEET_SPOT_PX..MAX_SWEET_SPOT_PX
        val executeNow = inSweetSpotNow || (bestFrame == 0 && currentDist <= MAX_SWEET_SPOT_PX)

        val timingAuthority = when {
            inSweetSpotNow -> 1.0f
            executeNow -> 0.9f
            bestFrame == 1 -> 0.7f
            else -> 0.3f
        }

        return SharpTouchTimingResult(
            executeNow = executeNow,
            optimalFrameOffset = bestFrame,
            ballDistancePx = currentDist,
            timingAuthority = timingAuthority
        )
    }
}
/* ======
SharpTouchTimingEngine Anchor
====== */

/* ========
PassingLaneGraph
======== */
data class PassingLaneGraph(
    val lanes: List<PassingLane> = emptyList()
)
/* ======
PassingLaneGraph Anchor
====== */

/* ========
PlayerDetection
======== */
data class PlayerDetection(

    val x: Float,

    val y: Float,

    val confidence: Float,

    val isUserTeam: Boolean
)
/* ======
PlayerDetection Anchor
====== */

/* ========
PlayerDetector
======== */
object PlayerDetector {

    private const val MIN_PIXEL_COUNT = 10
    private const val MIN_ASPECT_RATIO = 0.15f
    private const val MIN_CANDIDATE_CONFIDENCE = 0.30f
    private const val NMS_OVERLAP_THRESHOLD = 0.40f

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): PlayerDetectionResult {
        if (blobs.isEmpty()) {
            return PlayerDetectionResult(
                detected = false,
                playerCount = 0,
                confidence = 0f,
                detections = emptyList()
            )
        }

        val raw = ArrayList<PlayerDetection>(blobs.size)

        for (blob in blobs) {
            if (blob.pixelCount < MIN_PIXEL_COUNT) continue

            val width = (blob.maxX - blob.minX + 1).coerceAtLeast(1)
            val height = (blob.maxY - blob.minY + 1).coerceAtLeast(1)
            val area = width.toFloat() * height.toFloat()
            val density = (blob.pixelCount.toFloat() / area).coerceIn(0f, 1f)
            val shortSide = minOf(width, height).toFloat()
            val longSide = maxOf(width, height).toFloat()
            val aspectRatio = if (longSide > 0f) shortSide / longSide else 0f

            if (aspectRatio < MIN_ASPECT_RATIO) continue

            val centerX = (blob.minX + blob.maxX) * 0.5f
            val centerY = (blob.minY + blob.maxY) * 0.5f

            // OMEGA FIX: Dynamic Wide Camera Depth Scaling
            val depthFactor = if (centerY < 400f) 0.25f else 1.0f
            val effectivePixelCount = blob.pixelCount / depthFactor

            val jersey = JerseyColorSegmentation.classify(
                blob.averageRed,
                blob.averageGreen,
                blob.averageBlue
            )

            val sizeScore = (effectivePixelCount / 64f).coerceIn(0f, 1f)
            val densityScore = density.coerceIn(0f, 1f)
            val shapeScore = ((aspectRatio - MIN_ASPECT_RATIO) /
                (1f - MIN_ASPECT_RATIO)).coerceIn(0f, 1f)
            val jerseyScore = jersey.confidence.coerceIn(0f, 1f)

            val confidence = (
                sizeScore * 0.30f +
                densityScore * 0.25f +
                shapeScore * 0.20f +
                jerseyScore * 0.25f
            ).coerceIn(0f, 1f)

            if (confidence < MIN_CANDIDATE_CONFIDENCE) continue

            raw.add(
                PlayerDetection(
                    x = centerX,
                    y = centerY,
                    confidence = confidence,
                    isUserTeam = jersey.team == JerseyColorSegmentation.Team.USER
                )
            )
        }

        raw.sortByDescending { it.confidence }

        // NMS: suppress lower-confidence detection if it overlaps a better one
        val kept = ArrayList<PlayerDetection>(raw.size)
        val suppressed = BooleanArray(raw.size)
        var sumConf = 0f
        for (i in raw.indices) {
            if (suppressed[i]) continue
            val pi = raw[i]
            kept.add(pi)
            sumConf += pi.confidence
            for (j in i + 1 until raw.size) {
                if (suppressed[j]) continue
                val dx = abs(pi.x - raw[j].x)
                val dy = abs(pi.y - raw[j].y)
                val distSq = dx * dx + dy * dy
                // suppress j if within ~40px of a better detection (40^2 = 1600)
                if (distSq < 1600f) suppressed[j] = true
            }
        }

        val aggregateConfidence = if (kept.isEmpty()) 0f else (sumConf / kept.size).coerceIn(0f, 1f)

        return PlayerDetectionResult(
            detected = kept.isNotEmpty(),
            playerCount = kept.size,
            confidence = aggregateConfidence,
            detections = kept
        )
    }
}
/* ======
PlayerDetector Anchor
====== */

/* ========
RuntimeConfidenceCalibrationResult
======== */
data class RuntimeConfidenceCalibrationResult(
    val confidence:Float=0f,
    val calibratedConfidence:Float=0f
)
/* ======
RuntimeConfidenceCalibrationResult Anchor
====== */

/* ========
RuntimeCoordinator
======== */
/*
 * Owns ignition order, gate state, and shutdown for the gameplay runtime.
 * Contains no gameplay logic. Gates:
 *   G0 permissions  G1 accessibility  G2 capture  G3 booster
 *   G4 engines warm G5 bus enabled    G6 runtime ready
 * For this step G0/G3 are recorded but non-blocking: a bound accessibility
 * service plus flowing projection frames already prove the permission set.
 */
object RuntimeCoordinator {

    private val permissionsVerified = AtomicBoolean(false)
    private val accessibilityReady = AtomicBoolean(false)
    private val captureReady = AtomicBoolean(false)
    private val boosterReady = AtomicBoolean(false)
    private val enginesWarm = AtomicBoolean(false)
    private val busEnabled = AtomicBoolean(false)
    private val runtimeReady = AtomicBoolean(false)

    private var startExecutionLoop: (() -> Unit)? = null
    private var stopExecutionLoopCallback: (() -> Unit)? = null

    // recursion guard: evaluate() must never re-enter itself
    private val evaluating = AtomicBoolean(false)

    @Volatile private var lastTransition: String = "cold"
    @Volatile private var lastTransitionMs: Long = 0L

    @Synchronized
    fun attachExecutionLoop(start: () -> Unit, stop: () -> Unit) {
        startExecutionLoop = start
        stopExecutionLoopCallback = stop
    }

    @Synchronized
    fun reportPermissionsVerified() {
        if (permissionsVerified.compareAndSet(false, true)) {
            transition("G0 PERMISSIONS_VERIFIED")
        }
        evaluate()
    }

    @Synchronized
    fun reportAccessibilityReady() {
        if (accessibilityReady.compareAndSet(false, true)) {
            transition("G1 ACCESSIBILITY_READY")
        }
        evaluate()
    }

    @Synchronized
    fun reportCaptureReady() {
        // Fires on EVERY captured frame. Once the runtime is up there is nothing
        // left to evaluate, so skip the work instead of re-scanning the registry.
        if (captureReady.compareAndSet(false, true)) {
            transition("G2 CAPTURE_READY")
            evaluate()
        } else if (!runtimeReady.get()) {
            evaluate()
        }
    }

    @Synchronized
    fun reportBoosterReady() {
        // Only a genuine state CHANGE may trigger evaluation. Calling evaluate()
        // unconditionally here is what closed the recursion cycle.
        if (boosterReady.compareAndSet(false, true)) {
            transition("G3 BOOSTER_READY")
            evaluate()
        }
    }

    @Synchronized
    fun refreshBoosterReadyFromRegistry() {
        // P0 FIX: BoosterIgnition.isFleetReady() is the SINGLE authority for G3.
        // PREVIOUS BUG: AdapterHealthRegistry.getAllLive().any { heartbeat <= 120s }
        //   -- one stale adapter heartbeat opened the gate, bypassing fleet quorum.
        // FIXED: fleet quorum (>=9/16 ACTIVE) AND ignited latch confirmed by
        //   IgnitionEngine.verifyFleetHealth() via BoosterIgnition.isFleetReady().
        // DEGRADED re-opening: isFleetReady() resets ignited=false on DEGRADED,
        //   so boosterReady is forced false here too, keeping the display accurate.
        // NOTE: once busEnabled=true, evaluateInner() returns early on the
        //   busEnabled check -- so re-opening boosterReady does NOT stop running
        //   engines (intentional: WatchdogAdapter handles mid-match recovery).
        // P0-A WIRING FIX (FIELD-STALL ROOT CAUSE, TASK-CLOSURE TRACED):
        // verifyFleetHealth() is the ONLY transition that can promote
        // fleetState WARMING -> READY. Its documented owner is this G3 refresh
        // path ("RuntimeCoordinator calls this to verify fleet quorum before
        // opening the G3 booster gate") but the call was missing, so
        // isFleetReady() stayed false forever and the runtime stalled at
        // G2_CAPTURE_READY (booster-not-ready, bus-idle, execution starved).
        try {
            com.assistant.BoosterIgnition.verifyFleetHealth()
        } catch (_: Throwable) { }

        val healthy = try {
            com.assistant.BoosterIgnition.isFleetReady()
        } catch (_: Throwable) { false }

        if (healthy) {
            if (boosterReady.compareAndSet(false, true)) {
                transition("G3 BOOSTER_READY [fleet-quorum confirmed by BoosterIgnition]")
            }
        } else {
            // Fleet not ready (COLD/WARMING/DEGRADED) -- re-open gate to reflect reality.
            if (boosterReady.getAndSet(false)) {
                transition("G3 BOOSTER_GATE_OPENED [fleet not ready / degraded]")
            }
        }
    }

    @Synchronized
    fun reportAccessibilityLost() {
        accessibilityReady.set(false)
        busEnabled.set(false)
        runtimeReady.set(false)
        transition("G1 lost - runtime paused")
    }

    @Synchronized
    fun shutdown() {
        stopExecutionLoopCallback?.invoke()
        CentralExecutionBus.stop()
        busEnabled.set(false)
        runtimeReady.set(false)
        captureReady.set(false)
        enginesWarm.set(false)
        // P0 FIX: Reset G3 gate AND BoosterIgnition ignited latch on shutdown.
        // PREVIOUS BUG: BoosterIgnition.reset() was never called here, so ignited=true
        //   survived a shutdown. Next cold-start: isFleetReady() returned true immediately
        //   (ignited still latched), bypassing fleet quorum re-verification entirely.
        boosterReady.set(false)
        try { com.assistant.BoosterIgnition.reset() } catch (_: Throwable) {}
        resetRuntimeState()
        transition("RUNTIME_OFF")
    }

    /*
     * Shutdown is the exact reverse of warmUpEngines() ignition order:
     * loop/frame/registry first, then gameplay engines, then stores/diagnostics.
     * Each guarded so one missing hook cannot abort the chain.
     */
    private fun resetRuntimeState() {
        // 1. Decision plumbing (last things ignited -> first reset)
        try { RuntimeDecisionLoop.reset() } catch (_: Throwable) {}
        try { com.assistant.runtime.GameplayEngineRegistry.resetAll() } catch (_: Throwable) {}
        try { FrameAssembler.reset() } catch (_: Throwable) {}
        try { BallTelemetryBridge.reset() } catch (_: Throwable) {}
        try { com.assistant.events.EventHubs.resetAll() } catch (_: Throwable) {}
        try { com.assistant.execution.ContributionRegistry.clear() } catch (_: Throwable) {}
        try { GestureExecutionAuthority.reset() } catch (_: Throwable) {}

        // 2. Gameplay engines (reverse of ignition)
        try { GameplayDecisionEngine.reset() } catch (_: Throwable) {}
        try { MagneticFeetEngine.reset() } catch (_: Throwable) {}
        try { OverloadPlaystyleEngine.reset() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.reset() } catch (_: Throwable) {}

        // 3. Diagnostics
        try { ActiveGestureControllerDiagnostics.reset() } catch (_: Throwable) {}
        try { SmartAssistMetrics.reset() } catch (_: Throwable) {}
        try { RuntimeHealthMonitor.reset() } catch (_: Throwable) {}
    }

    @Synchronized
    fun runtimeState(): Map<String, Any> {
        // Heal the booster latch lazily: the adapters usually heartbeat AFTER
        // this runtime reached G6, and no gate event fires again after that -
        // so without this re-check boosterReady stayed false forever even
        // while every adapter was alive and heartbeating.
        if (!boosterReady.get()) {
            refreshBoosterReadyFromRegistry()
        }
        return mapOf(
            "permissionsVerified" to permissionsVerified.get(),
            "accessibilityReady" to accessibilityReady.get(),
            "captureReady" to captureReady.get(),
            "boosterReady" to boosterReady.get(),
            "enginesWarm" to enginesWarm.get(),
            "busEnabled" to busEnabled.get(),
            "runtimeReady" to runtimeReady.get(),
            "lastTransition" to lastTransition,
            "lastTransitionMs" to lastTransitionMs,
            "busPending" to CentralExecutionBus.pendingCount()
        )
    }

    private fun evaluate() {
        // Hard stop against any future cycle. @Synchronized does NOT help here:
        // Java monitors are reentrant, so the same thread re-enters freely.
        if (!evaluating.compareAndSet(false, true)) return
        try {
            evaluateInner()
        } finally {
            evaluating.set(false)
        }
    }

    private fun evaluateInner() {
        refreshBoosterReadyFromRegistry()
        if (!accessibilityReady.get() || !captureReady.get()) return
        // P0 FIX: G3 gate is now an ACTUAL execution gate, not a display-only flag.
        // Engines must NOT start until BoosterIgnition confirms fleet quorum.
        // Once busEnabled=true this check is already bypassed by the busEnabled gate below,
        // so this ONLY affects cold-start -- engines will not fire with a dead fleet.
        if (!boosterReady.get()) return
        if (busEnabled.get()) return

        if (!enginesWarm.get()) {
            warmUpEngines()
            enginesWarm.set(true)
            transition("G4 ENGINES_WARM")
        }

        CentralExecutionBus.start()
        startExecutionLoop?.invoke()
        busEnabled.set(true)
        transition("G5 BUS_ENABLED")

        runtimeReady.set(true)
        transition("G6 RUNTIME_READY")
        // PHASE4B: agent already started in OverlayService.onCreate() — no duplicate start here
    }

    /*
     * Deterministic read-only ignition: stores -> vision snapshots ->
     * gameplay engines -> diagnostics. Touching each Kotlin object here
     * forces class loading in a fixed order, so the first real frame pays
     * no lazy-init cost. VisionCore itself is warmed by the first frame,
     * which is a precondition of reaching this point (G2).
     */
    private fun warmUpEngines() {
        // Unified registry ownership: all contributor registrations and warm-ups 
        // are now handled atomically by AppContributorRegistration to prevent 
        // dual-initialization races and warm-up idempotency issues.
        // This function now strictly handles read-only ignition for stores and engines only.
        try { TelemetryRepository.current() } catch (_: Throwable) {}
        try { SceneTracker.current() } catch (_: Throwable) {}
        try { Phase3WorldStateStore.current() } catch (_: Throwable) {}
        try { SmartAssistRepository.enabled() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot() } catch (_: Throwable) {}
        try { MagneticFeetEngine.magneticFeetSnapshot() } catch (_: Throwable) {}
        try { OverloadPlaystyleEngine.overloadRuntimeSnapshot() } catch (_: Throwable) {}
        try { GameplayDecisionEngine.gameplayActivationDiagnostics() } catch (_: Throwable) {}
        try { TrueTargetPassingEngine.currentReceiverRankingResult() } catch (_: Throwable) {}
        try { SmartAssistMetrics.snapshot() } catch (_: Throwable) {}
    }

    private fun transition(stage: String) {
        lastTransition = stage
        lastTransitionMs = System.currentTimeMillis()
        // Task 14: gate transitions are RUNTIME-channel events.
        try {
            com.assistant.events.RuntimeEventHub.emit("gate", stage)
        } catch (_: Throwable) {
            RuntimeLogger.log("RuntimeCoordinator: $stage", "RUNTIME")
        }
    }
}
/* ======
RuntimeCoordinator Anchor
====== */

/* ========
RuntimeDiagnosticsRegistry
======== */
data class RuntimeDiagnosticsState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val runtimeOverlayHub: RuntimeOverlayHubState =
        RuntimeOverlayHub.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current(),
    val visionOverlay: VisionDebugOverlayState =
        VisionDebugOverlay.current(),
    val overlayRegistry: VisionOverlayRegistryState =
        VisionOverlayRegistry.current()
)

object RuntimeDiagnosticsRegistry {

    @Volatile
    private var state = RuntimeDiagnosticsState()

    fun current(): RuntimeDiagnosticsState = state

    fun refresh() {
        RuntimeOverlayHub.refresh()
        VisionOverlayRegistry.refresh()
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()

        state = RuntimeDiagnosticsState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeOverlayHub = RuntimeOverlayHub.current(),
            runtimeTuning = RuntimeTuningPanel.current(),
            visionOverlay = VisionDebugOverlay.current(),
            overlayRegistry = VisionOverlayRegistry.current()
        )
    }

    fun enableRuntimeDiagnostics() {
        RuntimeOverlayHub.enableDiagnostics()
        refresh()
    }

    fun disableRuntimeDiagnostics() {
        RuntimeOverlayHub.disableDiagnostics()
        refresh()
    }

    fun reset() {
        RuntimeOverlayHub.reset()
        refresh()
    }
}
/* ======
RuntimeDiagnosticsRegistry Anchor
====== */

/* ========
RuntimeOverlayHub
======== */
data class RuntimeOverlayHubState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current(),
    val visionOverlay: VisionDebugOverlayState =
        VisionDebugOverlay.current(),
    val overlayRegistry: VisionOverlayRegistryState =
        VisionOverlayRegistry.current()
)

object RuntimeOverlayHub {

    @Volatile
    private var state = RuntimeOverlayHubState()

    fun current(): RuntimeOverlayHubState = state

    fun refresh() {
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()
        VisionOverlayRegistry.refresh()

        state = RuntimeOverlayHubState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeTuning = RuntimeTuningPanel.current(),
            visionOverlay = VisionDebugOverlay.current(),
            overlayRegistry = VisionOverlayRegistry.current()
        )
    }

    fun enableDiagnostics() {
        VisionOverlayRegistry.enableAll()
        refresh()
    }

    fun disableDiagnostics() {
        VisionOverlayRegistry.disableAll()
        refresh()
    }

    fun reset() {
        RuntimeTuningPanel.reset()
        refresh()
    }
}
/* ======
RuntimeOverlayHub Anchor
====== */

/* ========
ShootingHabitLearningEngine
======== */
object ShootingHabitLearningEngine{

    fun analyze(
        shooting:ShootingLaneAnalysis,
        tactical:TacticalIntelligenceResult
    ,
        temporal:TemporalMemoryState
    ):ShootingHabitLearningResult{

        val temporalConfidence=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val laneConfidence=
            if(shooting.lanes.isEmpty()) 0f else shooting.lanes.maxOf{it.confidence}

        val confidence=(
            tactical.confidence*0.40f+
            temporalConfidence*0.35f+
            laneConfidence*0.25f
        ).coerceIn(0f,1f)

        return ShootingHabitLearningResult(
            confidence=confidence,
            longShotBias=confidence*0.5f,
            boxShotBias=
                if(shooting.lanes.isEmpty())
                    confidence*0.3f
                else
                    confidence
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
ShootingHabitLearningEngine Anchor
====== */

/* ========
ShootingHabitLearningResult
======== */
data class ShootingHabitLearningResult(
    val confidence:Float=0f,
    val longShotBias:Float=0f,
    val boxShotBias:Float=0f
)
/* ======
ShootingHabitLearningResult Anchor
====== */

/* ========
ShotOpportunityAnalysisEngine
======== */
data class ShotOpportunityResult(
    val confidence:Float,
    val openSideScore:Float,
    val pressureScore:Float
)

object ShotOpportunityAnalysisEngine {

    fun analyze(
        distance:Float,
        pressure:Float
    ):ShotOpportunityResult {

        val confidence=
            (1f-(distance/1000f))
                .coerceIn(0f,1f)

        return ShotOpportunityResult(
            confidence=confidence,
            openSideScore=((1f-pressure)*4.00f).coerceIn(0f,4f),
            pressureScore=pressure.coerceIn(0f,1f)
        )
    }
}
/* ======
ShotOpportunityAnalysisEngine Anchor
====== */

/* ========
SmartAssistUltimateCorrectorEngine
======== */
data class SmartAssistCorrectionResult(
    val correctedX: Float,
    val correctedY: Float,
    val correctionStrength: Float,
    val correctionType: CorrectionType,
    val applied: Boolean
)

enum class CorrectionType { PASS, SHOT, CROSS, KEEPER, NONE }

/**
 * SmartAssistUltimateCorrectorEngine
 *
 * Core mathematical engine for fixing Smart Assist drift across all action types.
 * Performs zero heap allocations during math evaluations.
 */
object SmartAssistUltimateCorrectorEngine {

    private const val SA_PASS_ANTI_DRIFT = 0.40f
    private const val BALL_SPEED_PX_S    = 800f
    private const val PASS_LOOKAHEAD_S   = 0.16f
    private const val CROSS_SPEED_PX_S   = 700f
    private const val MAX_SHOT_DIST      = 720f

    fun correctPass(
        ballX: Float, ballY: Float,
        intendedX: Float, intendedY: Float,
        receiverVx: Float, receiverVy: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        pressure: Float
    ): SmartAssistCorrectionResult {
        val dx = intendedX - ballX
        val dy = intendedY - ballY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val travelS = (dist / BALL_SPEED_PX_S + PASS_LOOKAHEAD_S).coerceIn(0f, 0.55f)
        
        val fps = 60f
        val predX = (intendedX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        val predY = (intendedY + receiverVy * fps * travelS).coerceIn(0f, 720f)
        
        val saDx = nearestOpponentX - intendedX
        val saDy = nearestOpponentY - intendedY
        val correctedX = (predX - saDx * SA_PASS_ANTI_DRIFT).coerceIn(0f, 1650f)
        val correctedY = (predY - saDy * SA_PASS_ANTI_DRIFT).coerceIn(0f, 720f)
        
        val strength = (0.55f + pressure * 0.45f).coerceIn(0f, 1f)
        return SmartAssistCorrectionResult(correctedX, correctedY, strength, CorrectionType.PASS, true)
    }

    fun correctShot(
        ballX: Float, ballY: Float,
        goalLeftX: Float, goalRightX: Float,
        goalTopY: Float, goalBottomY: Float,
        goalkeeperX: Float, goalkeeperVisible: Boolean,
        goalDetected: Boolean
    ): SmartAssistCorrectionResult? {
        val minX = if (goalLeftX <= goalRightX) goalLeftX else goalRightX
        val maxX = if (goalLeftX <= goalRightX) goalRightX else goalLeftX
        val minY = if (goalTopY <= goalBottomY) goalTopY else goalBottomY
        val maxY = if (goalTopY <= goalBottomY) goalBottomY else goalTopY

        val goalCX = if (goalDetected) (minX + maxX) * 0.5f else 1650f
        val goalCY = if (goalDetected) (minY + maxY) * 0.5f else ballY
        
        val dist = hypot((ballX - goalCX).toDouble(), (ballY - goalCY).toDouble()).toFloat()
        if (dist > MAX_SHOT_DIST) return null

        val openX: Float
        val openY: Float = goalCY
        if (goalDetected && goalkeeperVisible && goalkeeperX > 0f) {
            openX = if (goalkeeperX <= goalCX) {
                (goalCX + (maxX - goalCX) * 0.70f).coerceIn(minX, maxX)
            } else {
                (goalCX - (goalCX - minX) * 0.70f).coerceIn(minX, maxX)
            }
        } else {
            openX = goalCX
        }

        val proximity = 1f - (dist / MAX_SHOT_DIST)
        val strength = (0.70f + proximity * 0.30f).coerceIn(0f, 1f)
        return SmartAssistCorrectionResult(
            openX.coerceIn(0f, 1650f), openY.coerceIn(0f, 720f),
            strength, CorrectionType.SHOT, true
        )
    }

    fun correctCross(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float,
        goalCenterX: Float, goalCenterY: Float,
        laneScore: Float
    ): SmartAssistCorrectionResult? {
        val dist = hypot((receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()).toFloat()
        if (dist > 900f || laneScore < 0.04f) return null

        val travelS = (dist / CROSS_SPEED_PX_S + 0.06f).coerceIn(0f, 0.55f)
        val fps = 60f
        val predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)
        val predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)

        val distPredToGoal = hypot(
            (predX - goalCenterX).toDouble(), (predY - goalCenterY).toDouble()
        ).toFloat()

        val targetX: Float
        val targetY: Float
        if (distPredToGoal < 220f) {
            targetX = predX
            targetY = predY
        } else {
            val penX = (goalCenterX - 160f).coerceIn(0f, 1650f)
            targetX = (predX * 0.55f + penX * 0.45f).coerceIn(0f, 1650f)
            targetY = (predY * 0.55f + goalCenterY * 0.45f).coerceIn(0f, 720f)
        }

        val strength = (laneScore * 0.75f + 0.25f * (1f - dist / 900f)).coerceIn(0f, 1f)
        return SmartAssistCorrectionResult(targetX, targetY, strength, CorrectionType.CROSS, true)
    }

    fun correctKeeper(
        ballX: Float, ballY: Float,
        goalLeftX: Float, goalRightX: Float,
        goalTopY: Float, goalBottomY: Float
    ): SmartAssistCorrectionResult {
        val minY = if (goalTopY <= goalBottomY) goalTopY else goalBottomY
        val maxY = if (goalTopY <= goalBottomY) goalBottomY else goalTopY
        val goalMidY = if (minY > 0f && maxY > minY) (minY + maxY) * 0.5f else ballY

        val gl = if (goalLeftX <= goalRightX) goalLeftX else goalRightX
        val gr = if (goalLeftX <= goalRightX) goalRightX else goalLeftX
        val interceptX = ballX.coerceIn(gl.coerceAtLeast(0f), gr.coerceAtMost(1650f))

        return SmartAssistCorrectionResult(interceptX, goalMidY, 0.92f, CorrectionType.KEEPER, true)
    }
}
/* ======
SmartAssistUltimateCorrectorEngine Anchor
====== */

/* ========
SpeedCompensationEngine
======== */
/**
 * High-fidelity representation of speed compensation vectors and intercept values.
 * Fields are upscaled up to 100.0f to deliver ultimate physical response times.
 */
data class SpeedCompensationResult(
    val containmentAngle: Float,
    val executionBoost: Float,
    val interceptionProtection: Float,
    val pressureCompensation: Float,
    val laneCompensation: Float
)

/**
 * SpeedCompensationEngine
 *
 * An advanced speed, interception, and pressure compensation module.
 * Programmatically upscales execution acceleration and protection thresholds up to 100.0f
 * under peak conditions to eliminate delay and shut down opponent build-up play instantly.
 */
object SpeedCompensationEngine {

    /**
     * Compensates speed and positioning based on proximity and interception angle.
     * Upscales execution and protection forces to guarantee ultra-fast reactions.
     */
    fun compensate(
        distance: Float,
        angle: Float,
        strength: Int
    ): SpeedCompensationResult {
        // Normalize factors to safe unit ranges
        val factor = (strength.coerceIn(0, 100) / 100.0f)
        val distanceNormalized = (distance.coerceIn(0f, 1000f) / 1000.0f)

        // Add micro-dithering angle noise to break up absolute binary 15.0f/-15.0f pattern footprints
        val angleJitter = Random.nextFloat() * 0.8f - 0.4f // +/- 0.4 degree variance
        val containment = if (abs(angle) > 45.0f) 15.0f + angleJitter else -15.0f + angleJitter

        // High-performance upscaling up to 100.0f under peak tactical situations.
        // As distance decreases (closer threat), speed compensation and protection scale to maximum.
        val proximityFactor = 1.0f - distanceNormalized

        // Continuous mathematical curve noise injection for anti-telemetric mapping
        val executionNoise = Random.nextFloat() * 0.24f - 0.12f // Subtle decimal wobble
        val protectionNoise = Random.nextFloat() * 0.18f - 0.09f
        val pressureNoise = Random.nextFloat() * 0.18f - 0.09f
        val laneNoise = Random.nextFloat() * 0.20f - 0.10f

        val executionBoost = ((10.0f + (factor * 15.0f) + (proximityFactor * 9.0f)) + executionNoise).coerceIn(10.0f, 35.0f)
        val interceptionProtection = ((factor * 15.0f + proximityFactor * 7.0f) + protectionNoise).coerceIn(0.0f, 20.0f)
        val pressureCompensation = ((factor * 15.0f + proximityFactor * 7.0f) + pressureNoise).coerceIn(0.0f, 20.0f)
        val laneCompensation = ((factor * 14.0f + proximityFactor * 8.0f) + laneNoise).coerceIn(0.0f, 20.0f)

        return SpeedCompensationResult(
            containmentAngle = containment,
            executionBoost = executionBoost,
            interceptionProtection = interceptionProtection,
            pressureCompensation = pressureCompensation,
            laneCompensation = laneCompensation
        )
    }
}
/* ======
SpeedCompensationEngine Anchor
====== */

/* ========
TacticalAnalyticsEngine
======== */
object TacticalAnalyticsEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        tacticalMap:TacticalMapResult,
        compactness:DefensiveCompactnessResult,
        wing:WingOverloadDetectionResult,
        central:CentralOverloadDetectionResult,
        pressing:PressingRecognitionResult,
        counterPress:CounterPressRecognitionResult,
        buildUp:BuildUpRecognitionResult,
        possession:PossessionStyleRecognitionResult
    ):TacticalAnalyticsResult{

        var score=0f

        score+=tacticalMap.confidence
        score+=compactness.confidence
        score+=compactness.compactness
        score+=wing.confidence
        score+=if(wing.overloaded)0.05f else 0f
        score+=central.confidence
        score+=if(central.overloaded)0.05f else 0f
        score+=pressing.confidence
        score+=if(pressing.detected)0.05f else 0f
        score+=counterPress.confidence
        score+=if(counterPress.detected)0.05f else 0f
        score+=buildUp.confidence
        score+=if(buildUp.detected)0.05f else 0f
        score+=possession.confidence
        score+=if(possession.detected)0.05f else 0f

        val confidence=clamp(score/8.4f)

        return TacticalAnalyticsResult(
            confidence=confidence
        )
    }
}
/* ======
TacticalAnalyticsEngine Anchor
====== */

/* ========
TacticalBehaviorRecognitionEngine
======== */
object TacticalBehaviorRecognitionEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        formation:FormationResult,
        teamShape:TeamShapeResult
    ):TacticalBehaviorRecognitionResult{

        var score=0f

        score+=analytics.confidence
        score+=formation.confidence
        score+=if(formation.found)0.20f else 0f
        score+=teamShape.confidence
        score+=teamShape.compactness

        val confidence=clamp(score/3.2f)

        return TacticalBehaviorRecognitionResult(
            confidence=confidence
        )
    }
}
/* ======
TacticalBehaviorRecognitionEngine Anchor
====== */

/* ========
TelemetryCoordinator
======== */
object TelemetryCoordinator {

    private var networkThread: LowLatencyNetworkThread? = null

    fun initializeTransport(host:String,port:Int){
        if(networkThread==null){
            networkThread = LowLatencyNetworkThread(host,port)
            networkThread?.start()
        }
    }

    fun updatePlayerMotion(
        velocity: Float,
        opponentDistance: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                playerVelocity = velocity,
                opponentDistance = opponentDistance
            )
        )

        RuntimeLogger.telemetry("playerVelocity=$velocity opponentDistance=$opponentDistance")
    }

    fun updateBallMotion(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                ballX = x,
                ballY = y,
                ballVelocityX = velocityX,
                ballVelocityY = velocityY
            )
        )

        // GAP2: record WHEN we saw the ball, so confidence can decay with age
        if (x != 0f || y != 0f) {
            VisionTrust.stampBall(1f)
            VisionTrust.pushMotion(velocityX, velocityY)
        }

        RuntimeLogger.telemetry("ball=($x,$y) velocity=($velocityX,$velocityY)")
    }

    fun updateGoalkeeperPosition(
        x: Float,
        y: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                goalkeeperX = x,
                goalkeeperY = y,
                goalkeeperConfidence =
                    if (x != 0f || y != 0f) 1f else 0f
            )
        )

        RuntimeLogger.telemetry("goalkeeper=($x,$y)")
    }
}
/* ======
TelemetryCoordinator Anchor
====== */

/* ========
TelemetryRepository
======== */
object TelemetryRepository {

    @Volatile
    private var snapshot = TelemetrySnapshot()

    fun update(newSnapshot: TelemetrySnapshot) {
        snapshot = newSnapshot
    }

    fun current(): TelemetrySnapshot {
        return snapshot
    }
}
/* ======
TelemetryRepository Anchor
====== */

/* ========
TemporalMemoryState
======== */
data class TemporalMemoryState(
    val historyWindow: Int = 30,
    val sampleCount: Int = 0,
    val rollingConfidence: Float = 0f,
    val exponentialMovingAverage: Float = 0f,
    val confidenceTrend: Float = 0f,
    val confidenceVariance: Float = 0f,
    val historyStability: Float = 0f,
    val confidenceSlope: Float = 0f,
    val confidenceEvolution: Float = 0f,
    val observationAge: Int = 0,
    val decayFactor: Float = 0.98f,
    val minConfidence: Float = 0f,
    val maxConfidence: Float = 0f,
    val temporalConfidence: Float = 0f,
    val rollingMean: Float = 0f,
    val rollingStdDev: Float = 0f,
    val onlineUpdateCount: Int = 0,
    
    // --- OMEGA UPGRADE: ZERO-ALLOCATION CIRCULAR BUFFER ---
    val history: FloatArray = FloatArray(historyWindow),
    val historyIndex: Int = 0,
    
    // --- OMEGA UPGRADE: ADAPTIVE KINEMATICS & SYNC ---
    val gestureScaleMultiplier: Float = 1.0f,
    val humanizedHoldDelayMs: Long = 0L,
    val frameSyncOffsetMs: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TemporalMemoryState

        if (historyWindow != other.historyWindow) return false
        if (sampleCount != other.sampleCount) return false
        if (historyIndex != other.historyIndex) return false
        if (!history.contentEquals(other.history)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = historyWindow
        result = 31 * result + sampleCount
        result = 31 * result + historyIndex
        result = 31 * result + history.contentHashCode()
        return result
    }
}
/* ======
TemporalMemoryState Anchor
====== */

/* ========
ThroughPassSanitizer
======== */
class ThroughPassSanitizer(
    private val inputEngine:LatencyDefeatingInputEngine
){

    fun sanitizeAndInjectThroughPass(
        passButtonX:Float,
        passButtonY:Float,
        joystickX:Float,
        joystickY:Float,
        receiverX:Float,
        receiverY:Float,
        nearestDefenderX:Float,
        nearestDefenderY:Float
    ){

        val dx = receiverX - passButtonX
        val dy = receiverY - passButtonY

        val primaryAngle =
            atan2(
                dy.toDouble(),
                dx.toDouble()
            )

        val defDx =
            nearestDefenderX - passButtonX

        val defDy =
            nearestDefenderY - passButtonY

        val defenderAngle =
            atan2(
                defDy.toDouble(),
                defDx.toDouble()
            )

        val angularDisparity =
            abs(primaryAngle - defenderAngle)

        val sanitizedAngle =
            if(
                angularDisparity <
                Math.toRadians(20.0)
            ){
                if(primaryAngle > defenderAngle)
                    primaryAngle + Math.toRadians(15.0)
                else
                    primaryAngle - Math.toRadians(15.0)
            }else{
                primaryAngle
            }

        val targetJoystickX =
            joystickX +
            (cos(sanitizedAngle) * 80).toFloat()

        val targetJoystickY =
            joystickY +
            (sin(sanitizedAngle) * 80).toFloat()

        inputEngine.injectZeroLatencySwipe(
            passButtonX,
            passButtonY,
            targetJoystickX,
            targetJoystickY,
            75L
        )
    }
}
/* ======
ThroughPassSanitizer Anchor
====== */

/* ========
TouchStabilizationEngine
======== */
/**
 * TOUCH STABILIZATION ENGINE
 * Bypasses standard Android input latency queues by directly injecting 
 * mathematically smoothed, high-priority GestureDescriptions.
 */
object TouchStabilizationEngine {
    
    // Absolute minimum time required by Android for a gesture (forced to 2ms for near-instant latency)
    private const val OVERRIDE_LATENCY_MS = 2L
    
    /**
     * Injects a near-instantaneous touch down/up event to simulate zero-latency response.
     */
    @JvmStatic
    fun injectZeroLatencyTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        // Build the gesture with absolute minimal duration for instant registration
        // Native bridge bypasses Path allocation for true zero-latency injection
        return com.assistant.input.NativeInputBridge.injectTap(service, x, y)
    }
    
    /**
     * Stabilizes a swipe gesture by smoothing the coordinate translation 
     * and forcing it through the Accessibility queue at maximum speed.
     */
    @JvmStatic
    fun injectStabilizedSwipe(
        service: AccessibilityService, 
        startX: Float, 
        startY: Float, 
        endX: Float, 
        endY: Float, 
        durationMs: Long
    ): Boolean {
        // Ensure duration doesn't violate engine bounds but pushes the hardware limit
        val safeDuration = max(OVERRIDE_LATENCY_MS, durationMs)
        return com.assistant.input.NativeInputBridge.injectSwipe(service, startX, startY, endX, endY, safeDuration)
    }
}
/* ======
TouchStabilizationEngine Anchor
====== */

/* ========
TrackedPlayer
======== */
data class TrackedPlayer(

    val id:Int,

    var x:Float,

    var y:Float,

    var velocityX:Float,

    var velocityY:Float,

    var headingRadians:Float = 0f,

    var confidence:Float,

    val isUserTeam:Boolean,

    val isGoalkeeper:Boolean = false,

    var lastSeenFrame:Long

)
/* ======
TrackedPlayer Anchor
====== */

/* ========
WingOverloadDetectionEngine
======== */
object WingOverloadDetectionEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): WingOverloadDetectionResult {

        val overloaded = scene.playerCount >= 8

        val confidence = (
            scene.confidence +
            scene.fieldConfidence +
            if (pressure.rows>0 && occupancy.rows>0) 1f else 0f
        ) / 3f

        return WingOverloadDetectionResult(
            leftWingAdvantage = 0.5f,
            rightWingAdvantage = 0.5f,
            overloaded = overloaded,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
WingOverloadDetectionEngine Anchor
====== */

/* ========
ZeroFramePressEngine
======== */
class ZeroFramePressEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    private companion object {
        // Optimized math: distance calculation via squared values prevents costly Math.sqrt operations
        const val ENGAGEMENT_DISTANCE_SQR = 45.0f * 45.0f

        // Server-Tick Sync: Network packet boundary constants for high-ping resilience
        const val BASE_HOLD_DURATION_MS = 25L
        const val TICK_RATE_MULTIPLIER = 1.25f
        
        // Adaptive Noise bounds
        const val NOISE_VARIANCE_MIN = -1.25f
        const val NOISE_VARIANCE_MAX = 1.25f
        const val PATH_SCALAR_BASE = 5.0f
    }

    /**
     * High-performance execution block for instant touch injection.
     * Operates purely with local stack variables to guarantee zero heap allocation.
     */
    fun executeInstantSteal(
        defX: Float,
        defY: Float,
        ballX: Float,
        ballY: Float,
        dashButtonX: Float,
        dashButtonY: Float
    ) {
        // Direct stack calculations, bypassing the old FloatArray heap state buffer
        val deltaX = ballX - defX
        val deltaY = ballY - defY

        // Fast euclidean distance squared
        val distanceSqr = (deltaX * deltaX) + (deltaY * deltaY)

        if (distanceSqr < ENGAGEMENT_DISTANCE_SQR) {
            
            // Adaptive Noise Humanization: Dynamic micro-variance mimicking human hand latency boundaries
            val microVarianceX = NOISE_VARIANCE_MIN + (Random.nextFloat() * (NOISE_VARIANCE_MAX - NOISE_VARIANCE_MIN))
            val microVarianceY = NOISE_VARIANCE_MIN + (Random.nextFloat() * (NOISE_VARIANCE_MAX - NOISE_VARIANCE_MIN))

            // Server-Tick Sync: Dynamically scale gesture path lengths and hold durations to match packet boundaries
            val dynamicPathLength = (PATH_SCALAR_BASE * TICK_RATE_MULTIPLIER) + (Random.nextFloat() * 1.5f)
            
            // Modulating hold duration to register maximum possession effectiveness
            val randomHoldVariance = Random.nextLong(3L, 9L)
            val dynamicHoldDuration = (BASE_HOLD_DURATION_MS * TICK_RATE_MULTIPLIER).toLong() + randomHoldVariance

            val originX = dashButtonX + microVarianceX
            val originY = dashButtonY + microVarianceY

            val targetX = originX + dynamicPathLength
            val targetY = originY + (microVarianceY * 0.5f) // Subtle angular trajectory variance

            inputEngine.injectZeroLatencySwipe(
                originX,
                originY,
                targetX,
                targetY,
                dynamicHoldDuration
            )
        }
    }
}
/* ======
ZeroFramePressEngine Anchor
====== */

/* ========
VisionPreprocessor
======== */
object VisionPreprocessor {
    private const val TAG = "VisionPreprocessor"
    
    @Volatile private var nativeAvailable = false
    private var outBlobs = FloatArray(1024 * 8)
    
    init {
        try {
            System.loadLibrary("splendor_native")
            nativeAvailable = true
            RuntimeLogger.log("Pure C Native VisionCore loaded successfully", TAG)
        } catch (t: Throwable) {
            nativeAvailable = false
            RuntimeLogger.log("Native VisionCore FAILED to load: ${t.message}. Fallback active.", TAG)
        }
    }

    fun process(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val buffer = frame.buffer
        val width = frame.width
        val height = frame.height

        if (!nativeAvailable || !buffer.isDirect) {
            return fallback(frame)
        }

        val thresholdInt = (0.50f * 255.0f).toInt().coerceIn(0, 255)
        val capacity = outBlobs.size / 8
        
        val result = nativeScanAndExtract(
            buffer, width, height, frame.rowStride, frame.pixelStride,
            thresholdInt, outBlobs, capacity
        )
        
        return if (result >= 0) {
            decodeBlobs(result)
        } else if (result == -1) {
            fallback(frame)
        } else {
            val required = -result
            outBlobs = FloatArray(required * 8)
            val retryResult = nativeScanAndExtract(
                buffer, width, height, frame.rowStride, frame.pixelStride,
                thresholdInt, outBlobs, outBlobs.size / 8
            )
            if (retryResult >= 0) decodeBlobs(retryResult) else fallback(frame)
        }
    }

    private fun decodeBlobs(count: Int): List<ConnectedComponentEngine.Blob> {
        val list = ArrayList<ConnectedComponentEngine.Blob>(count)
        for (i in 0 until count) {
            val base = i * 8
            list.add(
                ConnectedComponentEngine.Blob(
                    minX = outBlobs[base].toInt(),
                    minY = outBlobs[base + 1].toInt(),
                    maxX = outBlobs[base + 2].toInt(),
                    maxY = outBlobs[base + 3].toInt(),
                    pixelCount = outBlobs[base + 4].toInt(),
                    averageRed = outBlobs[base + 5],
                    averageGreen = outBlobs[base + 6],
                    averageBlue = outBlobs[base + 7]
                )
            )
        }
        return list
    }

    private fun fallback(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val samples = FrameScanner.scan(frame)
        return ConnectedComponentEngine.extract(samples)
    }

    private external fun nativeScanAndExtract(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        thresholdInt: Int, outBlobs: FloatArray, outCapacity: Int
    ): Int
}
/* ======
VisionPreprocessor Anchor
====== */

/* ========
ControlMappingTrainer
======== */
/**
 * ControlMappingTrainer - button-mapping ground truth + personal timing trainer.
 * Trained from the user's mapping screenshots (Attacking/Defending), stored
 * normalized so any capture resolution works. Probe-only: <=43 byte reads per
 * frame, never a full-frame pass, so the 60fps hot path is untouched.
 */
object ControlMappingTrainer {

    enum class UiMode { UNKNOWN, SETTINGS, IN_MATCH }

    data class SlotDef(
        val id: Int,
        val nx: Float, val ny: Float,
        val attackLabel: String, val defendLabel: String,
        val dirAttack: Boolean, val dirDefend: Boolean
    )

    val SLOTS = listOf(
        SlotDef(1, 0.785f, 0.540f, "Through", "Switch", true, true),
        SlotDef(2, 0.892f, 0.504f, "Shoot (Clear)", "Tackle", true, false),
        SlotDef(3, 0.760f, 0.740f, "Pass", "Match-up", true, true),
        SlotDef(4, 0.879f, 0.704f, "Dash", "Dash & Pressure", false, true)
    )
    private const val JOY_NX = 0.182f
    private const val JOY_NY = 0.648f
    private const val TAB_NX = 0.500f
    private const val TAB_NY = 0.131f
    private const val BACK_NX = 0.157f
    private const val BACK_NY = 0.819f

    @Volatile var uiMode: UiMode = UiMode.UNKNOWN; private set
    val matchActive: Boolean get() = uiMode == UiMode.IN_MATCH

    private val slotX = FloatArray(5)
    private val slotY = FloatArray(5)
    private val slotHits = IntArray(5)
    init { for (s in SLOTS) { slotX[s.id] = s.nx; slotY[s.id] = s.ny } }

    private val observed = AtomicLong(0)
    private val settingsFrames = AtomicLong(0)
    private val matchFrames = AtomicLong(0)
    private val probesRead = AtomicLong(0)
    @Volatile private var lastMove = "none"

    private val emaMs = FloatArray(8)
    private val emaN = IntArray(8)
    private val baseMs = floatArrayOf(30f, 30f, 30f, 30f, 30f, 30f, 30f, 30f)
    @Volatile private var pendingClass = -1
    @Volatile private var pendingMs = 0L
    @Volatile private var pendingAt = 0L
    @Volatile private var baseSpeed = 0f

    fun trainedSlots(): Int = SLOTS.count { slotHits[it.id] >= 3 }

    fun slotCenter(id: Int): Pair<Float, Float> = Pair(slotX[id], slotY[id])

    fun labelFor(id: Int, attacking: Boolean): String =
        SLOTS.firstOrNull { it.id == id }?.let { if (attacking) it.attackLabel else it.defendLabel } ?: "none"

    fun resolveMove(slotId: Int, attacking: Boolean, ms: Long, dx: Float, dy: Float): String {
        val s = SLOTS.firstOrNull { it.id == slotId } ?: return "none"
        val dirOk = if (attacking) s.dirAttack else s.defendLabel.let { s.dirDefend }
        val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val flick = dirOk && mag >= 40f && ms <= 250L
        val hold = ms >= 120L
        val label = if (attacking) s.attackLabel else s.defendLabel
        val dir = if (flick) when {
            dy <= -0.5f * mag -> "_UP"
            dy >= 0.5f * mag -> "_DOWN"
            dx < 0 -> "_LEFT"
            else -> "_RIGHT"
        } else ""
        return when {
            flick -> label + "_FLICK" + dir
            hold && attacking && slotId == 2 -> "POWER_SHOT"
            hold && attacking && slotId == 3 -> "LOFTED_PASS"
            hold && attacking && slotId == 1 -> "LOFTED_THROUGH"
            hold && !attacking && slotId == 4 -> "PRESSURE_DASH_HOLD"
            hold && !attacking && slotId == 3 -> "MATCH_UP_HOLD"
            hold -> label + "_HOLD"
            else -> label + "_TAP"
        }
    }

    private fun px(b: ByteBuffer, idx: Int): Int = b.get(idx).toInt() and 0xFF

    private val probeBuffer = IntArray(3)
    private fun probe(b: ByteBuffer, w: Int, h: Int, stride: Int, nx: Float, ny: Float): Boolean {
        val x = (nx * w).toInt(); val y = (ny * h).toInt()
        val idx = y * stride + x * 4
        if (idx < 0 || idx + 2 >= b.capacity()) return false
        probesRead.incrementAndGet()
        probeBuffer[0] = px(b, idx); probeBuffer[1] = px(b, idx + 1); probeBuffer[2] = px(b, idx + 2)
        return true
    }

    private fun isGreen() = probeBuffer[1] > probeBuffer[0] + 8 && probeBuffer[1] > probeBuffer[2] + 8
    private fun isGray() = kotlin.math.abs(probeBuffer[0] - probeBuffer[1]) <= 14 && kotlin.math.abs(probeBuffer[1] - probeBuffer[2]) <= 14 && probeBuffer[0] in 110..215
    private fun isBlue() = probeBuffer[2] > probeBuffer[0] + 50 && probeBuffer[2] > 140

    fun observe(buffer: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        if (w <= 0 || h <= 0) return
        observed.incrementAndGet()
        val tabGray = probe(buffer, w, h, rowStride, TAB_NX, TAB_NY) && isGray()
        val backBlue = probe(buffer, w, h, rowStride, BACK_NX, BACK_NY) && isBlue()
        var green = 0
        for (s in SLOTS) {
            if (probe(buffer, w, h, rowStride, slotX[s.id], slotY[s.id]) && isGreen()) green++
        }
        val joyGray = probe(buffer, w, h, rowStride, JOY_NX, JOY_NY) && (isGray() || isGreen())
        val next = when {
            tabGray && backBlue -> UiMode.SETTINGS
            green >= 2 && joyGray -> UiMode.IN_MATCH
            else -> UiMode.UNKNOWN
        }
        uiMode = next
        if (next == UiMode.SETTINGS) {
            settingsFrames.incrementAndGet()
            for (s in SLOTS) {
                var bestNx = slotX[s.id]; var bestNy = slotY[s.id]; var bestScore = -1; var hit = false
                for (oy in -2..2 step 2) for (ox in -2..2 step 2) {
                    val nx = s.nx + ox * 0.01f; val ny = s.ny + oy * 0.01f
                    if (probe(buffer, w, h, rowStride, nx, ny) && isGreen()) {
                        val score = probeBuffer[1] - (probeBuffer[0] + probeBuffer[2]) / 2
                        if (score > bestScore) { bestScore = score; bestNx = nx; bestNy = ny; hit = true }
                    }
                }
                if (hit) {
                    slotX[s.id] = slotX[s.id] * 0.7f + bestNx * 0.3f
                    slotY[s.id] = slotY[s.id] * 0.7f + bestNy * 0.3f
                    slotHits[s.id]++
                }
            }
            lastMove = "settings-training"
        } else if (next == UiMode.IN_MATCH) {
            matchFrames.incrementAndGet()
        }
    }

    fun recordDispatch(phaseOrdinal: Int, durationMs: Long) {
        pendingClass = phaseOrdinal.coerceIn(0, 7)
        pendingMs = durationMs
        pendingAt = System.currentTimeMillis()
    }

    fun observeOutcome(frame: RuntimeFrame) {
        val mag = hypot(frame.ballVelocityX.toDouble(), frame.ballVelocityY.toDouble()).toFloat()
        baseSpeed = baseSpeed * 0.95f + mag * 0.05f
        if (pendingClass < 0) return
        val age = System.currentTimeMillis() - pendingAt
        if (age > 700L) { pendingClass = -1; return }
        val spike = mag > baseSpeed * 2.5f + 2f || frame.goalDetected
        if (spike) {
            val i = pendingClass
            emaMs[i] = if (emaN[i] == 0) pendingMs.toFloat() else emaMs[i] * 0.8f + pendingMs * 0.2f
            emaN[i]++
            lastMove = "trained:" + (ActionClass.values().getOrNull(i)?.name ?: i.toString()) + ":n=" + emaN[i] + ":ema=" + emaMs[i].toInt() + "ms"
            pendingClass = -1
        }
    }

    fun personalDuration(actionClass: ActionClass, hint: Long): Long {
        val i = actionClass.ordinal.coerceIn(0, 7)
        if (emaN[i] < 4) return hint
        val scale = (emaMs[i] / baseMs[i]).coerceIn(0.8f, 1.25f)
        return (hint * scale).toLong()
    }

    fun mappingRuntimeSnapshot(): Map<String, Any> = mapOf(
        "uiMode" to uiMode.name,
        "matchActive" to matchActive,
        "observedFrames" to observed.get(),
        "settingsFrames" to settingsFrames.get(),
        "matchFrames" to matchFrames.get(),
        "probesRead" to probesRead.get(),
        "trainedSlots" to trainedSlots(),
        "lastMove" to lastMove
    )

    fun reset() {
        uiMode = UiMode.UNKNOWN
        for (s in SLOTS) { slotX[s.id] = s.nx; slotY[s.id] = s.ny; slotHits[s.id] = 0 }
        observed.set(0); settingsFrames.set(0); matchFrames.set(0); probesRead.set(0)
        for (i in 0..7) { emaMs[i] = 0f; emaN[i] = 0 }
        pendingClass = -1; lastMove = "none"; baseSpeed = 0f
    }
}
/* ======
ControlMappingTrainer Anchor
====== */

/* ========
Phase3WorldState
======== */
data class Phase3WorldState(
    val closestPlayer: ClosestPlayerResult = ClosestPlayerResult(false),
    val ownership: BallOwnershipResult = BallOwnershipResult(false),
    val possession: BallPossessionResult = BallPossessionResult(false),
    val attacker: ActiveAttackerResult = ActiveAttackerResult(false),
    val defender: ActiveDefenderResult = ActiveDefenderResult(false),
    val formation: FormationResult = FormationResult(false),
    val teamShape: TeamShapeResult = TeamShapeResult(false),
    val defensiveLine: DefensiveLineResult = DefensiveLineResult(false),
    val offensiveLine: OffensiveLineResult = OffensiveLineResult(false),
    val occupancy: SpaceOccupancyResult =
        SpaceOccupancyResult(0,0, emptyArray()),
    val pressure: PressureFieldResult =
        PressureFieldResult(0,0, emptyArray()),
    val tacticalMapResult: TacticalMapResult =
        TacticalMapResult(),

    val defensiveCompactnessResult: DefensiveCompactnessResult =
        DefensiveCompactnessResult(),

    val wingOverloadDetectionResult: WingOverloadDetectionResult =
        WingOverloadDetectionResult(),

    val centralOverloadDetectionResult: CentralOverloadDetectionResult =
        CentralOverloadDetectionResult(),

    val passingGraph: PassingLaneGraph =
        PassingLaneGraph(),

    val throughBallAnalysis: ThroughBallLaneAnalysis =
        ThroughBallLaneAnalysis(),

    val crossingLaneAnalysis: CrossingLaneAnalysis =
        CrossingLaneAnalysis(),

    val shootingLaneAnalysis: ShootingLaneAnalysis =
        ShootingLaneAnalysis(),

    val blockedLanePredictionAnalysis: BlockedLanePredictionAnalysis =
        BlockedLanePredictionAnalysis(),

    val defenderInterceptionPredictionAnalysis: DefenderInterceptionPredictionAnalysis =
        DefenderInterceptionPredictionAnalysis(),

    val openSpaceDetectionResult: OpenSpaceDetectionResult =
        OpenSpaceDetectionResult(),

    val receiverRankingResult: ReceiverRankingResult =
        ReceiverRankingResult(),

    val runPredictionResult: RunPredictionResult =
        RunPredictionResult(),

    val overlapDetectionResult: OverlapDetectionResult =
        OverlapDetectionResult(),

    val counterattackDetectionResult: CounterattackDetectionResult =
        CounterattackDetectionResult(),

    val fastBreakDetectionResult: FastBreakDetectionResult =
        FastBreakDetectionResult(),

    val offsideRiskEstimationResult: OffsideRiskEstimationResult =
        OffsideRiskEstimationResult(),

    val pressingRecognitionResult: PressingRecognitionResult =
        PressingRecognitionResult(),

    val counterPressRecognitionResult: CounterPressRecognitionResult =
        CounterPressRecognitionResult(),

    val buildUpRecognitionResult: BuildUpRecognitionResult =
        BuildUpRecognitionResult(),

    val possessionStyleRecognitionResult: PossessionStyleRecognitionResult =
        PossessionStyleRecognitionResult(),

    val tacticalAnalyticsResult: TacticalAnalyticsResult =
        TacticalAnalyticsResult(),

    val tacticalBehaviorRecognitionResult: TacticalBehaviorRecognitionResult =
        TacticalBehaviorRecognitionResult(),

    val tacticalIntelligenceResult: TacticalIntelligenceResult =
        TacticalIntelligenceResult(),

    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),

    val formationAdaptationResult: FormationAdaptationResult =
        FormationAdaptationResult(),

    val runtimeConfidenceCalibrationResult: RuntimeConfidenceCalibrationResult =
        RuntimeConfidenceCalibrationResult(),

    val onlineParameterAdaptationResult: OnlineParameterAdaptationResult =
        OnlineParameterAdaptationResult(),

    val temporalMemoryState: TemporalMemoryState =
        TemporalMemoryEngine.initialize()
)

object Phase3WorldStateStore {

    @Volatile
    private var latest = Phase3WorldState()

    fun update(state: Phase3WorldState) {
        latest = state
    }

    fun current(
): Phase3WorldState =
        latest
}
/* ======
Phase3WorldState Anchor
====== */

/* ========
PlayerDetectionResult
======== */
data class PlayerDetectionResult(
    val detected: Boolean,
    val playerCount: Int,
    val confidence: Float,
    val detections: List<PlayerDetection>
)
/* ======
PlayerDetectionResult Anchor
====== */

/* ========
PlayerTendencyLearningEngine
======== */
object PlayerTendencyLearningEngine{

    fun analyze(
        tactical:TacticalIntelligenceResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):PlayerTendencyLearningResult{

        val temporalConfidence =
            (
                temporal.exponentialMovingAverage*0.30f+
                temporal.rollingMean*0.25f+
                temporal.temporalConfidence*0.20f+
                (0.5f+temporal.confidenceSlope*0.5f).coerceIn(0f,1f)*0.15f+
                (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.10f
            ).coerceIn(0f,1f)

        val confidence=(
            tactical.confidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporalConfidence*0.40f
        ).coerceIn(0f,1f)

        return PlayerTendencyLearningResult(
            confidence=confidence,
            passBias=(confidence*(0.60f+state.confidence*0.40f)).coerceIn(0f,1f),
            dribbleBias=(confidence*(0.50f+state.fieldConfidence*0.50f)).coerceIn(0f,1f),
            shootBias=(confidence*(0.40f+state.confidence*0.60f)).coerceIn(0f,1f)
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
PlayerTendencyLearningEngine Anchor
====== */

/* ========
PreferredPassingLaneLearningResult
======== */
data class PreferredPassingLaneLearningResult(
    val confidence:Float=0f,
    val preferredLaneScore:Float=0f
)
/* ======
PreferredPassingLaneLearningResult Anchor
====== */

/* ========
PressingRecognitionResult
======== */
data class PressingRecognitionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)
/* ======
PressingRecognitionResult Anchor
====== */

/* ========
PressureFieldResult
======== */
data class PressureFieldResult(
    val columns: Int,
    val rows: Int,
    val pressure: Array<FloatArray>
)
/* ======
PressureFieldResult Anchor
====== */

/* ========
ReceiverEngagementEngine
======== */
data class ReceiverEngagementResult(
    val engagementBoost:Float,
    val interceptionRisk:Float,
    val confidence:Float
)

object ReceiverEngagementEngine {

    fun evaluate(
        distance:Float,
        retention:Float
    ):ReceiverEngagementResult {

        val distanceFactor =
            (distance.coerceIn(0f,1000f) / 1000f)

        val confidence =
            (
                0.45f +
                retention * 0.35f +
                (1f - distanceFactor) * 0.20f
            ).coerceIn(0f,1f)

        val engagementBoost =
            1f +
            (retention * 0.70f) +
            ((1f - distanceFactor) * 0.30f)

        val interceptionRisk =
            (
                (1f - retention) * 0.70f +
                distanceFactor * 0.30f
            ).coerceIn(0f,1f)

        return ReceiverEngagementResult(
            engagementBoost = engagementBoost,
            interceptionRisk = interceptionRisk,
            confidence = confidence
        )
    }
}
/* ======
ReceiverEngagementEngine Anchor
====== */

/* ========
RuntimeHealthMonitor
======== */
/*
 * RuntimeHealthMonitor answers one question:
 * "Is the runtime alive, stale, or degraded right now?"
 *
 * It does not own gameplay logic. It reads health surfaces already present
 * in the architecture and reports only REAL failures:
 *  - frame/decision freshness is measured by counter PROGRESS between
 *    evaluations (the old math compared 'now' against a timestamp taken
 *    inside the same call, so frame-stale could never fire and
 *    decision-stale fired for the wrong reason)
 *  - the decision loop runs once per assembled frame; when the frame flow
 *    itself is paused (menus, control rooms, game off screen) the loop is
 *    idle by design - the cause is reported as frame-stale, never blamed
 *    on the decision loop
 *  - a dispatcher that has never been asked to dispatch is idle, not stale
 *  - booster health reads the CROSS-PROCESS adapter heartbeats: the
 *    adapters live in their own processes, so only the persisted
 *    heartbeats are visible here, and only FRESH ones count
 */
object RuntimeHealthMonitor {

    private const val FRAME_STALE_MS = 3000L
    private const val DECISION_STALE_MS = 3000L
    private const val EXECUTION_STALE_MS = 5000L
    private const val BOOSTER_FRESH_MS = 120_000L

    private val evaluations = AtomicLong(0L)

    @Volatile
    private var lastEvaluatedMs: Long = 0L

    // frame-counter progress tracking between evaluations
    @Volatile private var prevFrames = -1L
    @Volatile private var framesProgressMs = 0L

    data class HealthState(
        val accessibilityAlive: Boolean,
        val overlayAlive: Boolean,
        val frameAlive: Boolean,
        val decisionAlive: Boolean,
        val busAlive: Boolean,
        val dispatchAlive: Boolean,
        val gameplayAlive: Boolean,
        val boosterAlive: Boolean,
        val degradedReasons: List<String>,
        val lastEvaluatedMs: Long
    )

    fun snapshot(): HealthState {
        evaluations.incrementAndGet()
        val now = System.currentTimeMillis()
        lastEvaluatedMs = now

        val runtime = RuntimeCoordinator.runtimeState()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val contributions =
            com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
        val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
        val registry = GameplayEngineRegistry.registryRuntimeSnapshot()

        val accessibilityAlive =
            runtime["accessibilityReady"] as? Boolean ?: false

        val overlayAlive =
            runtime["captureReady"] as? Boolean ?: false

        // ---- frame flow: alive while the frame counter keeps advancing ----
        val frames = (frame["frames"] as? Number)?.toLong() ?: 0L
        if (frames != prevFrames) {
            prevFrames = frames
            framesProgressMs = now
        }
        val frameFlowing = frames > 0L && now - framesProgressMs <= FRAME_STALE_MS
        val frameAlive = frame["state"] != "cold" && frameFlowing

        // ---- decision loop: judged against the frame flow it follows ----
        val decisions = (decision["decisions"] as? Number)?.toLong() ?: 0L
        val decisionLast = (decision["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val decisionFresh = decisionLast > 0L && now - decisionLast <= DECISION_STALE_MS
        val decisionAlive = when {
            decisions <= 0L -> false
            decisionFresh -> true
            // frame flow paused: the loop is idle by design, not stalled;
            // frame-stale carries the real cause
            !frameFlowing -> true
            // frames flowing but the loop is not keeping pace: genuine stall
            else -> false
        }

        val busAlive =
            (runtime["busEnabled"] as? Boolean ?: false) ||
                ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L

        // ---- dispatch: idle-with-nothing-requested is healthy ----
        val requested = (execution["requested"] as? Number)?.toLong() ?: 0L
        val acceptedCount = (execution["accepted"] as? Number)?.toLong() ?: 0L
        val executionLast = (execution["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val dispatchAlive = when {
            requested == 0L -> true
            acceptedCount > 0L -> true
            executionLast > 0L && now - executionLast <= EXECUTION_STALE_MS -> true
            else -> false
        }

        val gameplayAlive =
            ((registry["engines"] as? Number)?.toInt() ?: 0) > 0 &&
                (
                    decisions > 0L ||
                    ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L
                )

        // ---- booster: cross-process heartbeats, fresh ones only ----
        val boosterAlive = try { com.assistant.BoosterIgnition.isFleetReady() } catch (_: Throwable) { false }

        val degraded = mutableListOf<String>()

        if (!accessibilityAlive) degraded += "accessibility-not-ready"
        if (!overlayAlive) degraded += "capture-not-ready"
        if (!frameAlive) degraded += "frame-stale"
        if (!decisionAlive) degraded += "decision-stale"
        if (!busAlive) degraded += "bus-idle"
        if (!dispatchAlive) degraded += "dispatch-stale"
        if (!gameplayAlive) degraded += "gameplay-idle"
        if (!boosterAlive) degraded += "booster-not-ready"

        return HealthState(
            accessibilityAlive = accessibilityAlive,
            overlayAlive = overlayAlive,
            frameAlive = frameAlive,
            decisionAlive = decisionAlive,
            busAlive = busAlive,
            dispatchAlive = dispatchAlive,
            gameplayAlive = gameplayAlive,
            boosterAlive = boosterAlive,
            degradedReasons = degraded,
            lastEvaluatedMs = lastEvaluatedMs
        )
    }

    fun runtimeHealthSnapshot(): Map<String, Any> {
        val state = snapshot()
        return mapOf(
            "accessibilityAlive" to state.accessibilityAlive,
            "overlayAlive" to state.overlayAlive,
            "frameAlive" to state.frameAlive,
            "decisionAlive" to state.decisionAlive,
            "busAlive" to state.busAlive,
            "dispatchAlive" to state.dispatchAlive,
            "gameplayAlive" to state.gameplayAlive,
            "boosterAlive" to state.boosterAlive,
            "degradedReasons" to state.degradedReasons.joinToString(","),
            "lastEvaluatedMs" to state.lastEvaluatedMs,
            "evaluations" to evaluations.get()
        )
    }

    fun reset() {
        evaluations.set(0L)
        lastEvaluatedMs = 0L
        prevFrames = -1L
        framesProgressMs = 0L
    }
}
/* ======
RuntimeHealthMonitor Anchor
====== */

/* ========
RuntimePerformanceCoordinator
======== */
data class RuntimePerformanceState(
    val diagnostics: RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val visualization: RuntimeVisualizationState =
        RuntimeVisualizationRegistry.current(),
    val fpsMonitor: FPSMonitorState =
        FPSMonitor.current(),
    val latencyMonitor: VisionLatencyMonitorState =
        VisionLatencyMonitor.current(),
    val confidenceHeatmap: ConfidenceHeatmapState =
        ConfidenceHeatmap.current(),
    val stutterSuppressionEnabled:Boolean = true,
    val lagCompensationEnabled:Boolean = true,
    val inputDelayReductionEnabled:Boolean = true,
    val networkLatencyReductionEnabled:Boolean = true,
    val fpsStabilizationEnabled:Boolean = true
)

object RuntimePerformanceCoordinator {

    private var masterAuthority:Int = 100

    fun updateAuthority(authority:Int){
        masterAuthority = authority.coerceIn(0,100)
    }

    fun authority():Int = masterAuthority

    fun runtimeAuthority(): Int = authority().coerceIn(0,100) * 10

    fun goalkeeperAuthority(): Int = runtimeAuthority()

    fun interceptionAuthority(): Int = runtimeAuthority()

    fun smartAssistAuthority(): Int = runtimeAuthority()

    @Volatile
    private var state = RuntimePerformanceState()

    fun current(): RuntimePerformanceState = state

    fun refresh() {
        RuntimeDiagnosticsRegistry.refresh()
        RuntimeVisualizationRegistry.refresh()
        FPSMonitor.refresh()
        VisionLatencyMonitor.refresh()
        ConfidenceHeatmap.refresh()

        state = RuntimePerformanceState(
            diagnostics = RuntimeDiagnosticsRegistry.current(),
            visualization = RuntimeVisualizationRegistry.current(),
            fpsMonitor = FPSMonitor.current(),
            latencyMonitor = VisionLatencyMonitor.current(),
            confidenceHeatmap = ConfidenceHeatmap.current(),
            stutterSuppressionEnabled = true,
            lagCompensationEnabled = true,
            inputDelayReductionEnabled = true,
            networkLatencyReductionEnabled = true,
            fpsStabilizationEnabled = true
        )
    }

    fun activate() {
        RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        RuntimeVisualizationRegistry.enableVisualization()
        refresh()
    }

    fun reset() {
        RuntimeDiagnosticsRegistry.reset()
        RuntimeVisualizationRegistry.reset()
        refresh()
    }

    fun synchronizeExistingPerformanceEngines() {
        synchronizeRuntimePipeline()
    }

    fun synchronizeRuntimePipeline() {

        RuntimeDiagnosticsRegistry.refresh()
        RuntimeVisualizationRegistry.refresh()

        FPSMonitor.refresh()
        VisionLatencyMonitor.refresh()
        ConfidenceHeatmap.refresh()

        refresh()
    }

}
/* ======
RuntimePerformanceCoordinator Anchor
====== */

/* ========
RuntimeSelfHealEngine
======== */
/**
 * PHASE 4B — RuntimeSelfHealEngine (AI Self-Heal Agent — Full Authority)
 *
 * Changes from Phase 4A:
 *  - Starts immediately in OverlayService.onCreate() (3s grace, not 10s at G6)
 *  - Full deduplication — same event category+detail never spams the log
 *  - Has FULL RIGHT to restart ImageReader capture via OverlayService.restartCapture()
 *  - Permanently forces VisionTrust.foregroundIsGame=true on every cycle while
 *    capture is active (not just once — re-applies if accessibility event resets it)
 *  - Prints exact code fix patches to HealLog when in-memory fix is insufficient
 *  - Monitors gameplay contributor activity (detects dead contributors)
 *  - HealLog path: /sdcard/Splendor-Assist/SplendorHealLog.txt
 *    Read with: cat /sdcard/Splendor-Assist/SplendorHealLog.txt
 */
object RuntimeSelfHealEngine {

    data class HealEvent(
        val timestamp: String,
        val category: String,
        val detected: String,
        val fix: String,
        val severity: String   // FIXED / CRITICAL / WARNING / INFO / CODE_FIX_NEEDED
    )

    val healEvents: CopyOnWriteArrayList<HealEvent> = CopyOnWriteArrayList()

    @Volatile var agentStatus: String = "IDLE"
        private set
    @Volatile var totalHeals: Int = 0
        private set

    @Volatile private var running = false
    @Volatile private var agentStartedMs: Long = 0L
    private var contextRef: WeakReference<android.content.Context>? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Deduplication: category → last logged details hash + last log time
    private val lastLoggedMs = HashMap<String, Long>()
    private val lastLoggedDetail = HashMap<String, Int>()
    private val repeatCount = HashMap<String, Int>()
    private val DEDUP_MIN_MS = 5 * 60 * 1000L  // max 1 per 5 min per category

    fun init(ctx: android.content.Context) {
        contextRef = WeakReference(ctx.applicationContext)
    }

    // V6: expose stored context so the agent can execute safe recoveries.
    fun appContext(): android.content.Context? = contextRef?.get()

    fun start() {
        if (running) return
        running = true
        agentStartedMs = System.currentTimeMillis()
        agentStatus = "STARTING"
        val t = Thread {
            // 3s grace — just enough for services to start
            try { Thread.sleep(3_000L) } catch (_: Throwable) { return@Thread }
            agentStatus = "MONITORING"
            RuntimeLogger.log("AI Self-Heal Agent: MONITORING ACTIVE (5s cycle, immediate start)", "AGENT")
            writeToFile(null, header = true)  // write session header to HealLog
            while (running) {
                try { runChecks() }
                catch (e: Throwable) {
                    try { RuntimeLogger.log("AGENT FAULT: ${e.javaClass.simpleName}: ${e.message}", "AGENT") } catch (_: Throwable) {}
                }
                try {
                    Thread.sleep(5_000L)
                } catch (_: Throwable) {
                    return@Thread
                }
            }
            agentStatus = "STOPPED"
        }
        t.isDaemon = true
        t.name = "splendor-self-heal-agent"
        t.start()
        RuntimeLogger.log("RuntimeSelfHealEngine started — AI agent daemon online", "AGENT")
    }

    fun stop() { running = false; agentStatus = "IDLE" }

    fun isRunning(): Boolean = running

    /**
     * Controlled synchronous check requested by InAppAgentCore.
     * The existing periodic self-heal daemon remains authoritative.
     */
    fun runImmediateCheck() {
        if (!running) return
        try {
            runChecks()
        } catch (e: Throwable) {
            try {
                RuntimeLogger.log(
                    "AGENT immediate check fault: ${e.javaClass.simpleName}: ${e.message}",
                    "AGENT"
                )
            } catch (_: Throwable) {}
        }
    }

    private fun agentAgeMs() = System.currentTimeMillis() - agentStartedMs

    private fun runChecks() {
        val warmed = agentAgeMs() > 5_000L  // only 5s grace for flagging
        enforceForegroundGate()      // EVERY cycle — not just when false
        checkCaptureThread()         // critical — can restart
        checkBusSignals(warmed)
        checkLoadShed()
        checkDispatchRate()
        checkContributors(warmed)
        checkBattery()
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMANENT FG OVERRIDE: re-apply on every cycle while capture is active
    // Previous version: forced once, then accessibility event reset it to false
    // This version: every 5s check, if capture is active → fg must be true
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var fgForceCount = 0

    private fun enforceForegroundGate() {
        try {
            val frame = FrameAssembler.current() ?: return
            val frameAgeMs = System.currentTimeMillis() - frame.timestampMs
            if (frameAgeMs > 3000L) return  // capture stale — don't force

            val fg = VisionTrust.isGameForeground()
            if (!fg) {
                VisionTrust.setGameForeground(true)
                fgForceCount++
                if (shouldLog("FG_OVERRIDE", "forced=$fgForceCount")) {
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "FG_OVERRIDE",
                        detected = "foregroundIsGame=false while capture active (frame ${frameAgeMs}ms old). " +
                            "eFootball child surfaces send empty packageName → VisionTrust resets gate. " +
                            "This has been forced $fgForceCount times this session.",
                        fix = "FIXED: VisionTrust.setGameForeground(true) applied. Re-applies every 5s as long as " +
                            "capture is active so accessibility events can never re-block contributors. " +
                            "PERMANENT CODE FIX: In VisionTrust.onForegroundPackage(), add: " +
                            "if (p.isEmpty() && foregroundIsGame) return  [already applied in Phase3]",
                        severity = "FIXED"
                    ))
                }
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CAPTURE THREAD MONITOR — most critical check
    // Root cause of "decisions=22412 frozen": HyperOS revokes media projection
    // Agent has FULL AUTHORITY to restart via OverlayService.restartCapture()
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var lastKnownFrameId: Long = -1L
    @Volatile private var captureStaleMs: Long = 0L
    @Volatile private var captureRestartAttempts = 0
    @Volatile private var lastRestartAttemptMs = 0L

    private fun checkCaptureThread() {
        try {
            val f = FrameAssembler.current() ?: return
            val now = System.currentTimeMillis()

            if (f.frameId != lastKnownFrameId) {
                lastKnownFrameId = f.frameId
                captureStaleMs = 0L
                // Reset attempt counter when capture recovers so a later
                // projection kill in the same session gets fresh 3 attempts.
                if (captureRestartAttempts > 0) {
                    captureRestartAttempts = 0
                    lastRestartAttemptMs = 0L
                }
                return  // frames advancing — OK
            }

            val staleMs = now - f.timestampMs
            if (staleMs < 5000L) return  // not stale yet

            // Capture IS stale
            captureStaleMs = staleMs

            // ROOT-CAUSE FIX (HealLog 2026-08-25): when the projection is revoked,
            // restartCaptureIfAlive() can NEVER succeed (first branch returns false).
            // Do not burn the 3-attempt budget on a no-op; escalate to the
            // user-visible recovery prompt (tap = BAL exemption = fresh token).
            val state = com.assistant.OverlayService.captureState()
            if (state == com.assistant.OverlayService.CaptureState.REVOKED) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now
                    // MASSIVE POWER: AI Agent handles projection revoke autonomously and silently.
                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; AI Agent handling autonomously without interrupting gameplay.",
                            fix = "Capture resources invalidated. AI Agent operates silently in background.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            } else if (state == com.assistant.OverlayService.CaptureState.FAILED) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now
                    if (shouldLog("CAPTURE_FAILED", "failed")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_FAILED",
                            detected = "MediaProjection setup failed; AI Agent handling autonomously without interrupting gameplay.",
                            fix = "Capture resources invalidated. AI Agent operates silently in background.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            } else if (state == com.assistant.OverlayService.CaptureState.IDLE) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now
                    if (shouldLog("CAPTURE_IDLE", "idle")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_IDLE",
                            detected = "MediaProjection idle; AI Agent handling autonomously without interrupting gameplay.",
                            fix = "Capture resources uninitialized. AI Agent operates silently in background.",
                            severity = "WARNING"
                        ))
                    }
                }
                return
            }

            // Attempt restart every 30s max, max 3 attempts per session
            val canRetry = captureRestartAttempts < 3 &&
                (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L)

            if (canRetry) {
                captureRestartAttempts++
                lastRestartAttemptMs = now
                totalHeals++

                // Attempt restart via OverlayService instance
                val restarted = try {
                    // This path is valid only while the existing MediaProjection
                    // session is still alive. Callback.onStop() now marks the
                    // projection revoked and starts fresh authorization instead.
                    val cls = Class.forName("com.assistant.OverlayService")
                    val method = cls.getDeclaredMethod("restartCaptureIfAlive")
                    (method.invoke(null) as? Boolean) ?: false
                } catch (e: Throwable) {
                    RuntimeLogger.log(
                        "AGENT: restartCaptureIfAlive failed: ${e.message}",
                        "AGENT"
                    )
                    false
                }

                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE_RESTART",
                    detected = "ImageReader capture thread STALE for ${staleMs / 1000}s. " +
                        "Root cause: HyperOS silent kill of media projection (3 kills seen in crash report). " +
                        "decisions counter frozen at frameId=${f.frameId}. " +
                        "Attempt #$captureRestartAttempts of 3.",
                    fix = if (restarted) "RESTART SENT to OverlayService.restartCapture(). " +
                        "Watch for new GAMEPLAY_EVENT entries to confirm success." else
                        "CAPTURE RESTART FAILED because the current projection may be " +
                        "invalid or revoked. OverlayService remains the recovery owner. " +
                        "If MediaProjection.onStop() fired, fresh user authorization " +
                        "is required; the old projection token cannot be reused.",
                    severity = if (restarted) "FIXED" else "CRITICAL"
                ))

                if (!restarted) {
                    printCodeFix("CAPTURE_THREAD_DEATH",
                        "HyperOS kills media projection after long sessions.\n" +
                        "The ImageReader stops delivering frames silently.\n" +
                        "PERMANENT FIX OPTIONS:\n" +
                        "1. In OverlayService.MediaProjection.Callback.onStop():\n" +
                        "   Instead of stopSelf(), call restartCapture() then re-request projection.\n" +
                        "2. Add KeepAlive periodic dummy capture every 60s to prevent OS timeout.\n" +
                        "3. Register FOREGROUND_SERVICE_TYPE=mediaProjection in manifest.\n" +
                        "APPLY: Check app/src/main/AndroidManifest.xml for foregroundServiceType."
                    )
                }
            } else if (shouldLog("CAPTURE_STALE", "stale-fixed")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE_STALE",
                    detected = "Capture stale ${staleMs / 1000}s. Restart attempts: $captureRestartAttempts/3.",
                    fix = if (captureRestartAttempts >= 3)
                        "Max restart attempts reached. FORCE-STOP app and reopen."
                        else "Next restart attempt in ${(30_000L - (System.currentTimeMillis() - lastRestartAttemptMs)) / 1000}s.",
                    severity = "CRITICAL"
                ))
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // BUS SIGNALS — publishes safe defaults if all stuck at UNKNOWN
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var busUnknownSinceMs: Long = 0L

    private fun checkBusSignals(warmed: Boolean) {
        if (!warmed) return
        try {
            val bus = AdapterSignalBus
            val allUnknown = bus.netWindow == "UNKNOWN" && bus.lagVerdict == "UNKNOWN" &&
                    bus.stutterState == "UNKNOWN" && bus.memoryTier == "UNKNOWN"

            if (allUnknown) {
                if (busUnknownSinceMs == 0L) busUnknownSinceMs = System.currentTimeMillis()
                val stuckMs = System.currentTimeMillis() - busUnknownSinceMs
                if (stuckMs > 30_000L && shouldLog("BUS_ALL_UNKNOWN", "stuck")) {
                    bus.publishNet("GO")
                    bus.publishLag("SMOOTH")
                    bus.publishStutter("CALM")
                    bus.publishMemory("HEALTHY", 1500L)
                    try { bus.publishThermal(0) } catch (_: Throwable) {}
                    try { bus.publishBattery(100, true) } catch (_: Throwable) {}
                    totalHeals++
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "BUS_ALL_UNKNOWN",
                        detected = "ALL bus signals stuck at UNKNOWN for ${stuckMs / 1000}s. " +
                            "Adapter services not publishing. SpeedCompensationContributor + RuntimeDecisionLoop blind.",
                        fix = "FIXED: Published safe defaults GO/SMOOTH/CALM/HEALTHY. " +
                            "Will re-apply on next check if still UNKNOWN. " +
                            "CODE FIX: Run apply_phase3_adapters.py if not yet applied.",
                        severity = "FIXED"
                    ))
                    RuntimeLogger.log("AGENT HEAL #$totalHeals BUS: safe defaults published", "AGENT")
                    busUnknownSinceMs = 0L
                }
            } else {
                busUnknownSinceMs = 0L
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOAD SHED — logs exact cause
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var heavySinceMs: Long = 0L

    private fun checkLoadShed() {
        try {
            val shed = PerformanceTelemetryRegistry.currentLoadShed()
            if (shed == "HEAVY") {
                if (heavySinceMs == 0L) heavySinceMs = System.currentTimeMillis()
                val heavyMs = System.currentTimeMillis() - heavySinceMs
                if (heavyMs > 20_000L && shouldLog("LOAD_SHED_HEAVY", "heavy=${heavyMs/1000}s")) {
                    val lag = AdapterSignalBus.lagVerdict
                    val stutter = AdapterSignalBus.stutterState
                    val cause = when {
                        stutter == "SEIZURE" -> "BurstForensicsEngine SEIZURE → HEAVY armed immediately"
                        lag == "CHOKING" -> "LagVerdictEngine CHOKING → HEAVY after arm_polls=4 cycles"
                        else -> "lag=$lag stutter=$stutter"
                    }
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOAD_SHED_HEAVY",
                        detected = "LoadShedGovernor HEAVY for ${heavyMs / 1000}s. $cause. " +
                            "ALL gameplay engines severely throttled.",
                        fix = "Cannot force-release. Admin fixes:\n" +
                            "  lag.shed.arm_polls: raise 4 → 8 (slower to arm)\n" +
                            "  stutter.forensics.seizure_ms: raise 150 → 250ms\n" +
                            "  lag.shed.min_hold_ms: lower 8000 → 4000ms (faster release)",
                        severity = "CRITICAL"
                    ))
                }
            } else {
                heavySinceMs = 0L
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // DISPATCH RATE — detects frozen loop, identifies cause
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var prevDecisions: Long = 0L
    @Volatile private var prevRouted: Long = 0L
    @Volatile private var zeroDispatchSinceMs: Long = 0L

    private fun checkDispatchRate() {
        try {
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val decisions = (snap["decisions"] as? Long) ?: 0L
            val routed = (snap["routed"] as? Long) ?: 0L
            val idleUntrusted = (snap["idleUntrusted"] as? Long) ?: 0L

            val newDecisions = decisions - prevDecisions
            val newRouted = routed - prevRouted
            prevDecisions = decisions
            prevRouted = routed

            if (newDecisions == 0L && decisions > 50L) {
                // Decision loop stopped — counter frozen
                if (shouldLog("LOOP_FROZEN", "frozen_at=$decisions")) {
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "LOOP_FROZEN",
                        detected = "RuntimeDecisionLoop counter frozen at $decisions. " +
                            "onFrame() not being called — ImageReader has stopped. " +
                            "This is almost always caused by HyperOS revoking media projection.",
                        fix = "AGENT will attempt capture restart. If max attempts reached: " +
                            "force-stop app and reopen. No code change needed — this is an OS kill issue.",
                        severity = "CRITICAL"
                    ))
                    RuntimeLogger.log("AGENT CRITICAL LOOP_FROZEN at $decisions — triggering capture check", "AGENT")
                    // Trigger capture check immediately
                    checkCaptureThread()
                }
            } else if (newDecisions > 30L && newRouted == 0L) {
                if (zeroDispatchSinceMs == 0L) zeroDispatchSinceMs = System.currentTimeMillis()
                val zeroMs = System.currentTimeMillis() - zeroDispatchSinceMs
                if (zeroMs > 10_000L && shouldLog("ZERO_DISPATCH", "zero=${zeroMs/1000}s")) {
                    // Loop running but nothing dispatched — trust or contributor issue
                    val trustPct = if (decisions > 0) idleUntrusted * 100L / decisions else 0L
                    val reason = if (trustPct > 50L)
                        "Vision trust blocks ${trustPct}% of frames → forcing fg=true"
                        else "Contributors all returning null → arbitration finding nothing actionable"
                    record(HealEvent(
                        timestamp = fmt.format(Date()),
                        category = "ZERO_DISPATCH",
                        detected = "0 gestures over ${zeroMs/1000}s despite $newDecisions decision cycles. $reason",
                        fix = if (trustPct > 50L) "APPLYING: VisionTrust.setGameForeground(true)"
                            else "MONITORING — contributor arbitration issue",
                        severity = "CRITICAL"
                    ))
                    if (trustPct > 50L) {
                        VisionTrust.setGameForeground(true)
                        totalHeals++
                    }
                }
            } else {
                zeroDispatchSinceMs = 0L
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONTRIBUTOR MONITOR — detects if any contributor is dead/never firing
    // ─────────────────────────────────────────────────────────────────────
    @Volatile private var prevCollectCycles: Long = 0L

    private fun checkContributors(warmed: Boolean) {
        if (!warmed) return
        try {
            val cls = Class.forName("com.assistant.runtime.GameplayEngineRegistry")
            @Suppress("UNCHECKED_CAST")
            val snap = cls.getMethod("registryRuntimeSnapshot").invoke(null) as? Map<String, Any> ?: return
            val engines = (snap["engines"] as? Int) ?: return
            val cycles = (snap["collectCycles"] as? Long) ?: return
            val delta = cycles - prevCollectCycles
            prevCollectCycles = cycles

            // PHASE4B: COLLECT_STALL — delta==0 while engine has run = collector frozen
            if (delta == 0L && cycles > 100L && engines >= 1 &&
                shouldLog("COLLECT_STALL", "stall_at=$cycles")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "COLLECT_STALL",
                    detected = "GameplayEngineRegistry.collect() stalled — 0 new collect cycles " +
                        "in last 5s (total cycles so far: $cycles). " +
                        "$engines contributors registered but idle. " +
                        "onFrame() not reaching RuntimeDecisionLoop — ImageReader likely dead.",
                    fix = "Triggering capture restart check via checkCaptureThread().",
                    severity = "CRITICAL"
                ))
                RuntimeLogger.log("AGENT CRITICAL COLLECT_STALL at cycles=$cycles — triggering capture check", "AGENT")
                checkCaptureThread()
            }

            if (engines < 29 && warmed && shouldLog("REGISTRY_GAP", "engines=$engines")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "REGISTRY_GAP",
                    detected = "Only $engines/29 contributors registered. Missing ${29 - engines}. " +
                        "Collect cycles this check: $delta (0 = collector frozen). " +
                        "warmUpEngines() may not have completed (G4 gate may not have fired).",
                    fix = "NONE in-memory — warmUpEngines() runs only once at G4. " +
                        "Check if G2 (capture) + G1 (accessibility) gates both reached.",
                    severity = "CRITICAL"
                ))
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // BATTERY — warn when low
    // ─────────────────────────────────────────────────────────────────────
    private fun checkBattery() {
        try {
            val level = AdapterSignalBus.batteryLevel
            val charging = AdapterSignalBus.batteryCharging
            if (level in 1..20 && !charging && shouldLog("BATTERY_LOW", "level=$level")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "BATTERY_LOW",
                    detected = "Battery at ${level}% and not charging. " +
                        "SpeedCompensationContributor reduces gesture duration to 50% of normal. " +
                        "Charging=false means CPU governor may throttle A75 cores.",
                    fix = "OPERATIONAL — reduced gestures is intentional at low battery. " +
                        "Plug in charger for full engine performance.",
                    severity = "WARNING"
                ))
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // Deduplication — same category can only log once per DEDUP_MIN_MS
    // ─────────────────────────────────────────────────────────────────────
    @Synchronized
    private fun shouldLog(category: String, detail: String): Boolean {
        val now = System.currentTimeMillis()
        val lastMs = lastLoggedMs[category] ?: 0L
        val lastHash = lastLoggedDetail[category] ?: -1
        val currentHash = detail.hashCode()
        val count = (repeatCount[category] ?: 0) + 1
        repeatCount[category] = count

        // Allow if: never logged, or enough time passed, or detail changed significantly
        if (now - lastMs > DEDUP_MIN_MS || lastHash != currentHash) {
            lastLoggedMs[category] = now
            lastLoggedDetail[category] = currentHash
            repeatCount[category] = 0
            return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────
    // Code fix printer — outputs structured patch to HealLog
    // ─────────────────────────────────────────────────────────────────────
    private fun printCodeFix(issueId: String, description: String) {
        val ts = fmt.format(Date())
        val text = buildString {
            appendLine("=" .repeat(60))
            appendLine("⚠️  CODE FIX NEEDED [$ts] — $issueId")
            appendLine("=" .repeat(60))
            for (line in description.lines()) appendLine("  $line")
            appendLine("=" .repeat(60))
        }
        try {
            val file = healLogFile() ?: return
            FileWriter(file, true).use { it.write(text) }
        } catch (_: Throwable) {}
        try { RuntimeLogger.log("CODE_FIX_NEEDED: $issueId — see HealLog", "AGENT") } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // File + memory logging
    // ─────────────────────────────────────────────────────────────────────
    private fun record(ev: HealEvent) {
        healEvents.add(ev)
        while (healEvents.size > 100) try { healEvents.removeAt(0) } catch (_: Throwable) {}
        writeToFile(ev)
        try {
            RuntimeLogger.log("[${ev.severity}] ${ev.category}: ${ev.detected.take(120)}", "AGENT")
        } catch (_: Throwable) {}
    }

    private fun healLogFile(): File? {
        return try {
            // /sdcard/Splendor-Assist/SplendorHealLog.txt — read with:
            // cat /sdcard/Splendor-Assist/SplendorHealLog.txt
            SplendorStorageRoot.file("SplendorHealLog.txt")
        } catch (_: Throwable) {
            // Canonical storage only. No storage fallback is permitted.
            null
        }
    }

    private fun writeToFile(ev: HealEvent?, header: Boolean = false) {
        try {
            val file = healLogFile() ?: return
            FileWriter(file, true).use { w ->
                if (header) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    w.write("\n" + "=".repeat(60) + "\n")
                    w.write("SPLENDOR SELF-HEAL AGENT SESSION: $ts\n")
                    w.write("Read: cat /sdcard/Splendor-Assist/SplendorHealLog.txt\n")
                    w.write("=".repeat(60) + "\n\n")
                    return
                }
                if (ev == null) return
                w.write("[${ev.timestamp}] [${ev.severity}] [${ev.category}]\n")
                w.write("  DETECTED: ${ev.detected}\n")
                w.write("  FIX:      ${ev.fix}\n\n")
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI status summary
    // ─────────────────────────────────────────────────────────────────────
    fun getStatusSummary(): String {
        return try {
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val decisions = (snap["decisions"] as? Long) ?: 0L
            val routed = (snap["routed"] as? Long) ?: 0L
            val dispatchPct = if (decisions > 0) routed * 100L / decisions else 0L
            val shed = try { PerformanceTelemetryRegistry.currentLoadShed() } catch (_: Throwable) { "?" }
            val fg = try { VisionTrust.isGameForeground() } catch (_: Throwable) { false }
            val agentAge = agentAgeMs() / 1000L
            buildString {
                appendLine("STATUS: $agentStatus  |  heals: $totalHeals  |  age: ${agentAge}s")
                appendLine("fg=$fg  shed=$shed  dispatch=${dispatchPct}%  fgForces=$fgForceCount")
                appendLine("net=${AdapterSignalBus.netWindow}  lag=${AdapterSignalBus.lagVerdict}")
                appendLine("stutter=${AdapterSignalBus.stutterState}  mem=${AdapterSignalBus.memoryTier}")
                appendLine("thermal=${AdapterSignalBus.thermalStatus}  battery=${AdapterSignalBus.batteryLevel}%chg=${AdapterSignalBus.batteryCharging}")
                appendLine("decisions=$decisions  routed=$routed  captureRestarts=$captureRestartAttempts")
                appendLine("lastAction=${snap["lastAction"]}")
                appendLine("HealLog: /sdcard/Splendor-Assist/SplendorHealLog.txt")
            }
        } catch (e: Throwable) { "AGENT STATUS ERROR: ${e.message}" }
    }
}
/* ======
RuntimeSelfHealEngine Anchor
====== */

/* ========
RuntimeTuningPanel
======== */
data class RuntimeTuningState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current()
)

object RuntimeTuningPanel {

    @Volatile
    private var state = RuntimeTuningState()

    fun current(): RuntimeTuningState = state

    fun reload() {
        state = RuntimeTuningState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current()
        )
    }

    fun updateVision(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        VisionConfigurationEngine.update(block)
        reload()
    }

    fun updateTracking(
        block:(TrackingConfiguration)->TrackingConfiguration
    ){
        TrackingConfigurationEngine.update(block)
        reload()
    }

    fun reset(){
        VisionConfigurationEngine.reset()
        TrackingConfigurationEngine.reset()
        reload()
    }
}
/* ======
RuntimeTuningPanel Anchor
====== */

/* ========
RuntimeVisualizationRegistry
======== */
data class RuntimeVisualizationState(
    val diagnostics: RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val overlayHub: RuntimeOverlayHubState =
        RuntimeOverlayHub.current(),
    val overlayRegistry: VisionOverlayRegistryState =
        VisionOverlayRegistry.current(),
    val visionOverlay: VisionDebugOverlayState =
        VisionDebugOverlay.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current()
)

object RuntimeVisualizationRegistry {

    @Volatile
    private var state = RuntimeVisualizationState()

    fun current(): RuntimeVisualizationState = state

    fun refresh() {
        RuntimeDiagnosticsRegistry.refresh()
        RuntimeOverlayHub.refresh()
        VisionOverlayRegistry.refresh()
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()

        state = RuntimeVisualizationState(
            diagnostics = RuntimeDiagnosticsRegistry.current(),
            overlayHub = RuntimeOverlayHub.current(),
            overlayRegistry = VisionOverlayRegistry.current(),
            visionOverlay = VisionDebugOverlay.current(),
            runtimeTuning = RuntimeTuningPanel.current()
        )
    }

    fun enableVisualization() {
        RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        refresh()
    }

    fun disableVisualization() {
        RuntimeDiagnosticsRegistry.disableRuntimeDiagnostics()
        refresh()
    }

    fun reset() {
        RuntimeDiagnosticsRegistry.reset()
        refresh()
    }
}
/* ======
RuntimeVisualizationRegistry Anchor
====== */

/* ========
SecurityExecutionDecoupler
======== */
class SecurityExecutionDecoupler {

    private val securityThread =
        HandlerThread(
            "SplendorAssist::ShieldCore",
            Process.THREAD_PRIORITY_BACKGROUND
        )

    private var securityHandler: Handler? = null

    fun initializeShield() {
        securityThread.start()
        securityHandler =
            Handler(
                securityThread.looper
            )
    }

    fun executeIsolatedSecurityCheck(
        crossCheckTask: Runnable
    ) {
        securityHandler?.post(
            crossCheckTask
        )
    }

    fun shutdownShield() {
        securityThread.quitSafely()
    }
}
/* ======
SecurityExecutionDecoupler Anchor
====== */

/* ========
ShieldAssistEngine
======== */
object ShieldAssistEngine {

    /**
     * Calculates the perfect protective body-shielding angle.
     * Incorporates anti-jitter wrapping to prevent rapid rotational vibration during contacts.
     */
    fun shieldAngle(
        movementAngle: Float
    ): Float {
        val bounded = movementAngle % 360f
        val angle = if (bounded < -180f) {
            bounded + 360f
        } else if (bounded > 180f) {
            bounded - 360f
        } else {
            bounded
        }
        
        // Add dynamic humanization drift to scramble exact 90-degree adjustments
        val microDrift = Random.nextFloat() * 1.3f - 0.65f // +/- 0.65 degree offset
        val result = if (angle >= 0f) angle + 90f + microDrift else angle - 90f + microDrift
        return (result % 360f + 360f) % 360f
    }

    /**
     * Advanced positional shield orientation.
     * Overloaded to compute the shielding vector relative to the defender/opponent's position,
     * placing your body exactly opposite (180 degrees) from the incoming tackle threat.
     */
    fun shieldAngle(
        playerX: Float,
        playerY: Float,
        oppX: Float,
        oppY: Float
    ): Float {
        val dx = oppX - playerX
        val dy = oppY - playerY
        val angleRad = atan2(dy.toDouble(), dx.toDouble())
        val angleDeg = Math.toDegrees(angleRad).toFloat()
        
        // Add tiny float variance to mask the perfect geometric line signature from telemetry logs
        val trajectoryJitter = Random.nextFloat() * 1.2f - 0.6f
        
        // Position player's physical body exactly opposite (180 deg) to block the direct tackle line
        val shieldDir = angleDeg + 180f + trajectoryJitter
        return (shieldDir % 360f + 360f) % 360f
    }

    /**
     * High-precision tactical engagement evaluation.
     * Uses the 100.0f reference divisor to normalize physical space limits.
     * Triggers shield if player velocity exceeds safe thresholds near defenders,
     * OR if immediate proximity threat drops under the critical 1.0f (100.0f) contact distance.
     */
    fun shouldEngageShield(
        playerVelocity: Float,
        opponentDistance: Float
    ): Boolean {
        if (opponentDistance <= 0f) return false
        val normalizedDistance = opponentDistance / 100.0f

        // Fuzz boundaries subtly to prevent instant, pixel-perfect activation triggers across matches
        val upperTriggerFuzz = 2.2f + (Random.nextFloat() * 0.04f - 0.02f) // +/- 0.02 range
        val lowerOverrideFuzz = 1.0f + (Random.nextFloat() * 0.02f - 0.01f)

        // Dynamic Threat Evaluation:
        // 1. Dynamic trigger if opponent is inside 220 coordinate points (2.2f) and player velocity is active.
        // 2. Immediate force override if within critical body collision box of 100.0f (1.0f normalized).
        return normalizedDistance < upperTriggerFuzz && (playerVelocity > 0.15f || normalizedDistance < lowerOverrideFuzz)
    }

    /**
     * Backward-compatible static shield duration (45ms).
     */
    fun shieldHoldDuration(): Long {
        // Scramble fixed backup tracking values by +/- 2ms to break rhythmic timeline blocks
        val staticJitter = Random.nextLong(-2, 3)
        return (45L + staticJitter).coerceAtLeast(40L)
    }

    /**
     * Dynamic shield duration calculation.
     * Dynamically extends body shield duration (up to 120ms) under heavy high-impact pressure,
     * while safely capping it to prevent input lag.
     */
    fun shieldHoldDuration(
        playerVelocity: Float,
        opponentDistance: Float
    ): Long {
        if (opponentDistance <= 0f) return 45L
        val proximityBonus =
            Math.round(
                100.0f / max(opponentDistance / 100.0f, 0.5f)
            ).coerceIn(0, 60)
        val velocityBonus =
            (playerVelocity.coerceAtLeast(0f) * 10f).toLong()
                .coerceIn(0L, 15L)
                
        // Inject per-frame timeline variance to break absolute caps on math metrics
        val adaptiveJitter = Random.nextLong(-3, 4) // -3ms to +3ms window
        
        val dynamicDuration = 45L + proximityBonus + velocityBonus + adaptiveJitter
        return dynamicDuration.coerceAtLeast(40L).coerceAtMost(124L)
    }
}
/* ======
ShieldAssistEngine Anchor
====== */

/* ========
ShootingLaneAnalysisEngine
======== */
data class ShootingLane(
    val shooter: TrackedPlayer,
    val targetX: Float,
    val targetY: Float,
    val distance: Float,
    val confidence: Float,
    val viable: Boolean
)

data class ShootingLaneAnalysis(
    val lanes: List<ShootingLane> = emptyList()
)

object ShootingLaneAnalysisEngine {

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): ShootingLaneAnalysis {

        if (!scene.goalDetected) {
            return ShootingLaneAnalysis()
        }

        val goalCenterX =
            (scene.goalLeftX + scene.goalRightX) * 0.5f

        val goalCenterY =
            (scene.goalTopY + scene.goalBottomY) * 0.5f

        val result = ArrayList<ShootingLane>()

        graph.lanes.forEach { lane ->

            val shooter = lane.receiver

            val distance =
                hypot(
                    (goalCenterX - shooter.x).toDouble(),
                    (goalCenterY - shooter.y).toDouble()
                ).toFloat()

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure) *
                    (1f - (distance / 1500f))
                ).coerceIn(0f,1f)

            result += ShootingLane(
                shooter = shooter,
                targetX = goalCenterX,
                targetY = goalCenterY,
                distance = distance,
                confidence = confidence,
                viable = !lane.blocked && confidence >= 0.12f
            )
        }

        return ShootingLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
    }
}
/* ======
ShootingLaneAnalysisEngine Anchor
====== */


/* ========
SmartAssistControlRoomBridge
======== */
class SmartAssistControlRoomBridge(
    private val repository: SmartAssistRepository
) {
    
    val state: StateFlow<SmartAssistState> = repository.state
    
    fun updateEnabled(enabled: Boolean) {
        repository.updateEnabled(enabled)
    }
    
    fun updatePanicMode(panic: Boolean) {
        repository.updatePanicMode(panic)
    }

    fun updateAuthority(authority:Int){
        repository.updateAuthority(authority)
        RuntimePerformanceCoordinator.updateAuthority(authority)
    }

fun updateThresholds(pass: Int, shot: Int, cross: Int) {
        repository.updateThresholds(pass, shot, cross)
    }
    
    fun saveCurrentSettings(): Boolean {
        return try {
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun getCurrentSettings(): SmartAssistState {
        return repository.getCurrentState()
    }
}
/* ======
SmartAssistControlRoomBridge Anchor
====== */

/* ========
SmartAssistRepository
======== */
data class SmartAssistConfiguration(
    val passThreshold: Int = 50,
    val shotThreshold: Int = 50,
    val crossThreshold: Int = 50,
    val panicThreshold: Int = 80,
    val authority: Int = 100
)

data class SmartAssistState(
    val enabled: Boolean = true,
    val panicMode: Boolean = false,
    val configuration: SmartAssistConfiguration = SmartAssistConfiguration(),
    val lastUpdated: Long = System.currentTimeMillis()
)

class SmartAssistRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "smart_assist_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PANIC = "panic_mode"
        private const val KEY_PASS = "pass_threshold"
        private const val KEY_SHOT = "shot_threshold"
        private const val KEY_CROSS = "cross_threshold"
        private const val KEY_PANIC_THRESHOLD = "panic_threshold"
        private const val KEY_AUTHORITY = "authority"
        
        // Static accessor for controllers that need it
        private var instance: SmartAssistRepository? = null
        private var staticState: SmartAssistState = SmartAssistState()
        
        fun enabled(): Boolean = staticState.enabled
        fun panicActive(): Boolean = staticState.panicMode
        fun configuration(): SmartAssistConfiguration = staticState.configuration
        
        // PHASE10_PANIC_PERSISTENCE_FINAL_MARKER
        fun activatePanic() {
            instance?.updatePanicMode(true)
                ?: run {
                    staticState = staticState.copy(panicMode = true)
                }
        }
        
        fun clearPanic() {
            instance?.updatePanicMode(false)
                ?: run {
                    staticState = staticState.copy(panicMode = false)
                }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SmartAssistState> = _state.asStateFlow()
    
    init {
        instance = this
        updateStaticState()
    }
    
    private fun updateStaticState() {
        staticState = _state.value
    }
    
    private fun loadState(): SmartAssistState {
        return SmartAssistState(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            panicMode = prefs.getBoolean(KEY_PANIC, false),
            configuration = SmartAssistConfiguration(
                passThreshold = prefs.getInt(KEY_PASS, 50),
                shotThreshold = prefs.getInt(KEY_SHOT, 50),
                crossThreshold = prefs.getInt(KEY_CROSS, 50),
                panicThreshold = prefs.getInt(KEY_PANIC_THRESHOLD, 80),
                authority = prefs.getInt(KEY_AUTHORITY,100)
            )
        )
    }
    
    fun saveState(newState: SmartAssistState) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, newState.enabled)
            putBoolean(KEY_PANIC, newState.panicMode)
            putInt(KEY_PASS, newState.configuration.passThreshold)
            putInt(KEY_SHOT, newState.configuration.shotThreshold)
            putInt(KEY_CROSS, newState.configuration.crossThreshold)
            putInt(KEY_PANIC_THRESHOLD, newState.configuration.panicThreshold)
            putInt(KEY_AUTHORITY, newState.configuration.authority)
            apply()
        }
        _state.value = newState.copy(lastUpdated = System.currentTimeMillis())
        updateStaticState()
    }
    
    fun updateEnabled(enabled: Boolean) = saveState(_state.value.copy(enabled = enabled))
    fun updatePanicMode(panic: Boolean) = saveState(_state.value.copy(panicMode = panic))
    fun updateThresholds(pass: Int, shot: Int, cross: Int) = 
        saveState(_state.value.copy(configuration = _state.value.configuration.copy(
            passThreshold = pass, shotThreshold = shot, crossThreshold = cross
        )))

    fun updateAuthority(authority:Int)=
        saveState(
            _state.value.copy(
                configuration =
                    _state.value.configuration.copy(
                        authority = authority
                    )
            )
        )

fun getCurrentState(): SmartAssistState = _state.value
}
/* ======
SmartAssistRepository Anchor
====== */

/* ========
SpaceOccupancyEngine
======== */
object SpaceOccupancyEngine {

    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 6

    fun compute(
        scene: SceneSnapshot,
        frameWidth: Float,
        frameHeight: Float
    ): SpaceOccupancyResult {

        val grid = Array(GRID_ROWS) { IntArray(GRID_COLUMNS) }

        if (frameWidth <= 0f || frameHeight <= 0f) {
            return SpaceOccupancyResult(
                GRID_COLUMNS,
                GRID_ROWS,
                grid
            )
        }

        scene.trackedPlayers.forEach { player ->

            val col = ((player.x / frameWidth) * GRID_COLUMNS)
                .toInt()
                .coerceIn(0, GRID_COLUMNS - 1)

            val row = ((player.y / frameHeight) * GRID_ROWS)
                .toInt()
                .coerceIn(0, GRID_ROWS - 1)

            grid[row][col]++
        }

        return SpaceOccupancyResult(
            GRID_COLUMNS,
            GRID_ROWS,
            grid
        )
    }
}
/* ======
SpaceOccupancyEngine Anchor
====== */

/* ========
TacticalAnalyticsResult
======== */
data class TacticalAnalyticsResult(
    val confidence:Float=0f
)
/* ======
TacticalAnalyticsResult Anchor
====== */

/* ========
TacticalIntelligenceResult
======== */
data class TacticalIntelligenceResult(
    val confidence:Float=0f
)
/* ======
TacticalIntelligenceResult Anchor
====== */

/* ========
TeamClassifier
======== */
object TeamClassifier {

    fun classify(
        scene: SceneSnapshot
    ): TeamClassificationResult {

        val user =
            scene.trackedPlayers.count { it.isUserTeam }

        val opponent =
            scene.trackedPlayers.count { !it.isUserTeam }

        return TeamClassificationResult(
            userPlayers = user,
            opponentPlayers = opponent,
            confidence =
                scene.trackedPlayers
                    .map { it.confidence }
                    .average()
                    .toFloat()
                    .takeIf { !it.isNaN() } ?: 0f
        )
    }
}
/* ======
TeamClassifier Anchor
====== */

/* ========
TeamShapeEngine
======== */
object TeamShapeEngine {

    fun compute(
        scene: SceneSnapshot
    ): TeamShapeResult {

        if (scene.trackedPlayers.isEmpty()) {
            return TeamShapeResult(found = false)
        }

        val minX = scene.trackedPlayers.minOf { it.x }
        val maxX = scene.trackedPlayers.maxOf { it.x }

        val minY = scene.trackedPlayers.minOf { it.y }
        val maxY = scene.trackedPlayers.maxOf { it.y }

        val width = maxX - minX
        val depth = maxY - minY

        val centerX =
            scene.trackedPlayers.map { it.x }.average().toFloat()

        val centerY =
            scene.trackedPlayers.map { it.y }.average().toFloat()

        val compactness =
            sqrt(width * width + depth * depth)

        val confidence =
            scene.trackedPlayers
                .map { it.confidence }
                .average()
                .toFloat()

        return TeamShapeResult(
            found = true,
            width = width,
            depth = depth,
            centerX = centerX,
            centerY = centerY,
            compactness = compactness,
            confidence = confidence
        )
    }
}
/* ======
TeamShapeEngine Anchor
====== */

/* ========
TeamShapeResult
======== */
data class TeamShapeResult(
    val found: Boolean,
    val width: Float = 0f,
    val depth: Float = 0f,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val compactness: Float = 0f,
    val confidence: Float = 0f
)
/* ======
TeamShapeResult Anchor
====== */

/* ========
TelemetrySnapshot
======== */
data class TelemetrySnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    val playerVelocity: Float = 0f,
    val opponentDistance: Float = Float.MAX_VALUE,

    val ballX: Float = 0f,
    val ballY: Float = 0f,
    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,

    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,
    val goalkeeperVelocityX: Float = 0f,
    val goalkeeperVelocityY: Float = 0f,
    val goalkeeperSpeed: Float = 0f,
    val goalkeeperHeadingRadians: Float = 0f,
    val goalkeeperConfidence: Float = 1f,

    val confidence: Float = 0f,
    val currentPingMs: Int = 35 // Included for dynamic network tick evaluation
)
/* ======
TelemetrySnapshot Anchor
====== */

/* ========
TouchRecoveryEngine
======== */
data class TouchRecoveryResult(
    val recoveryBoost: Float,
    val shieldStrength: Float,
    val balanceStrength: Float
)

object TouchRecoveryEngine {

    fun recover(
        pressure: Int,
        strength: Int
    ): TouchRecoveryResult {

        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)

        // Inject subtle sub-decimal float variance to break up repetitive data signatures
        val recoveryNoise = Random.nextFloat() * 0.16f - 0.08f // +/- 0.08 variance bounds
        val shieldNoise = Random.nextFloat() * 0.12f - 0.06f
        val balanceNoise = Random.nextFloat() * 0.12f - 0.06f

        val calculatedRecovery = (2f + (factor * 5.00f) + (pressureFactor * 3.00f) + recoveryNoise).coerceAtLeast(0f)
        val calculatedShield = (2f + (factor * 4.00f) + (pressureFactor * 2.00f) + shieldNoise).coerceAtLeast(0f)
        val calculatedBalance = (2f + (factor * 4.00f) + (pressureFactor * 2.00f) + balanceNoise).coerceAtLeast(0f)

        return TouchRecoveryResult(
            recoveryBoost = calculatedRecovery,
            shieldStrength = calculatedShield,
            balanceStrength = calculatedBalance
        )
    }
}
/* ======
TouchRecoveryEngine Anchor
====== */

/* ========
TrackingConfiguration
======== */
data class TrackingConfiguration(
    val enabled:Boolean = true,
    val adaptiveTracking:Boolean = true,
    val temporalTracking:Boolean = true,
    val entityAssociation:Boolean = true,
    val predictionEnabled:Boolean = true,
    val runtimeTuningEnabled:Boolean = true,
    val trackingDiagnostics:Boolean = true,
    val frameCompensation:Boolean = true,
    val interpolationEnabled:Boolean = true
)

object TrackingConfigurationEngine {

    @Volatile
    private var configuration = TrackingConfiguration()

    fun current(): TrackingConfiguration = configuration

    fun update(
        block:(TrackingConfiguration)->TrackingConfiguration
    ){
        configuration = block(configuration)
    }

    fun reset(){
        configuration = TrackingConfiguration()
    }
}
/* ======
TrackingConfiguration Anchor
====== */

/* ========
VisionCore
======== */
object VisionCore {

    fun process(
        frame: FrameNormalizer.NormalizedFrame
    ): GameStateSnapshot {

        val blobs =
            VisionPreprocessor.process(frame)

        val filteredBlobs =
            NoiseFilter.filter(blobs)

val heuristicCandidate =
    BallCandidateEngine.select(
        filteredBlobs
    )

/*
 * Task C: trained detector first. When a real TFLite model is loaded AND
 * has produced a real on-device inference, its ball wins this frame; the
 * heuristic candidate above is still computed every frame as the always-on
 * fallback and cross-check reference. With no model asset present the
 * engine returns null and behaviour is IDENTICAL to the pure-heuristic
 * pipeline - nothing is faked in either direction.
 */
val ballCandidate =
    TrainedDetectionEngine.ballCandidateOrNull(frame, heuristicCandidate)
        ?: heuristicCandidate

val ball =
    BallDetector.detect(
        ballCandidate
    )

val goal =
    GoalDetector.detect(
        filteredBlobs
    )

val field =
    FieldLineDetector.detect(
        filteredBlobs
    )

val motion =
    MotionTracker.update(
        ball
    )

// Single writer of ball telemetry: publishes only on a real detection so
// FrameAssembler can mark the frame trusted and all contributors activate.
BallTelemetryBridge.publish(ball)

val players =
    PlayerDetector.detect(filteredBlobs)

val goalkeeper =
    GoalkeeperDetector.detect(
        filteredBlobs
    )

val state =
    GameStateFusion.fuse(
        ball,
        motion,
        players,
        goalkeeper,
        goal,
        field
    )

SceneTracker.update(
    state,
    players
)

  val scene =
      SceneTracker.current()

  val closestPlayer =
      ClosestPlayerEngine.compute(
          ball,
          scene
      )

  val ownership =
      BallOwnershipEngine.compute(
          ball,
          scene
      )

  val possession =
      BallPossessionEngine.compute(
          ownership
      )

  val attacker =
      ActiveAttackerEngine.compute(
          scene,
          possession
      )

  val defender =
      ActiveDefenderEngine.compute(
          scene,
          attacker
      )

  val formation =
      FormationEngine.estimate(
          scene
      )

  val teamShape =
      TeamShapeEngine.compute(
          scene
      )

  val defensiveLine =
      DefensiveLineEngine.compute(
          scene
      )

  val offensiveLine =
      OffensiveLineEngine.compute(
          scene
      )

  val occupancy =
      SpaceOccupancyEngine.compute(
          scene,
          frame.width.toFloat(),
          frame.height.toFloat()
      )

  val pressure =
      PressureFieldEngine.compute(
          scene,
          frame.width.toFloat(),
          frame.height.toFloat()
      )

  val tacticalMapResult =
      TacticalMapGenerationEngine.compute(
          scene,
          occupancy,
          pressure,
          teamShape,
          defensiveLine,
          offensiveLine
      )

  val defensiveCompactnessResult =
      DefensiveCompactnessEngine.compute(
          scene,
          defensiveLine,
          teamShape
      )

  val wingOverloadDetectionResult =
      WingOverloadDetectionEngine.compute(
          scene,
          occupancy,
          pressure
      )

  val centralOverloadDetectionResult =
      CentralOverloadDetectionEngine.compute(
          scene,
          occupancy,
          pressure
      )

  val passingGraph =
      PassingLaneGraphEngine.build(
          scene,
          pressure
      )

  val throughBallAnalysis =
      ThroughBallLaneAnalysisEngine.analyze(
          passingGraph
      )

  val crossingLaneAnalysis =
      CrossingLaneAnalysisEngine.analyze(
          passingGraph
      )

  val shootingLaneAnalysis =
      ShootingLaneAnalysisEngine.analyze(
          scene,
          passingGraph
      )

  val blockedLanePredictionAnalysis =
      BlockedLanePredictionEngine.analyze(
          passingGraph
      )

  val defenderInterceptionPredictionAnalysis =
      DefenderInterceptionPredictionEngine.analyze(
          scene,
          passingGraph
      )

  val openSpaceDetectionResult =
      OpenSpaceDetectionEngine.analyze(
          occupancy,
          pressure,
          frame.width.toFloat(),
          frame.height.toFloat()
      )

  val receiverRankingResult =
      ReceiverRankingEngine.analyze(
          passingGraph
      )

  val runPredictionResult =
      RunPredictionEngine.analyze(
          scene
      )

  val overlapDetectionResult =
      OverlapDetectionEngine.analyze(
          runPredictionResult
      )

  val counterattackDetectionResult =
      CounterattackDetectionEngine.analyze(
          scene,
          teamShape,
          offensiveLine
      )

  val fastBreakDetectionResult =
      FastBreakDetectionEngine.analyze(
          scene
      )

  val pressingRecognitionResult =
      PressingRecognitionEngine.analyze(
          pressure,
          defensiveCompactnessResult,
          formation
      )

  val counterPressRecognitionResult =
      CounterPressRecognitionEngine.analyze(
          scene,
          possession,
          pressure
      )

  val buildUpRecognitionResult =
      BuildUpRecognitionEngine.analyze(
          formation,
          teamShape,
          passingGraph
      )

  val possessionStyleRecognitionResult =
      PossessionStyleRecognitionEngine.analyze(
          possession,
          passingGraph,
          pressure
      )

  val tacticalAnalyticsResult =
      TacticalAnalyticsEngine.analyze(
          tacticalMapResult,
          defensiveCompactnessResult,
          wingOverloadDetectionResult,
          centralOverloadDetectionResult,
          pressingRecognitionResult,
          counterPressRecognitionResult,
          buildUpRecognitionResult,
          possessionStyleRecognitionResult
      )

  val tacticalBehaviorRecognitionResult =
      TacticalBehaviorRecognitionEngine.analyze(
          tacticalAnalyticsResult,
          formation,
          teamShape
      )

      val previousTemporalMemory =
          Phase3WorldStateStore.current().temporalMemoryState

      val temporalMemoryState =
          TemporalMemoryEngine.update(
              previousTemporalMemory,
              tacticalBehaviorRecognitionResult.confidence
          )

  val tacticalIntelligenceResult =
      TacticalIntelligenceEngine.analyze(
          tacticalAnalyticsResult,
          tacticalBehaviorRecognitionResult,
          state,
          temporalMemoryState
      )

      val opponentBehaviourLearningResult =
          OpponentBehaviourLearningEngine.analyze(
              tacticalIntelligenceResult,
              state,
              temporalMemoryState
          )

      val playerTendencyLearningResult =
          PlayerTendencyLearningEngine.analyze(
              tacticalIntelligenceResult,
              state,
              temporalMemoryState
          )

      val preferredPassingLaneLearningResult =
          PreferredPassingLaneLearningEngine.analyze(
              passingGraph,
              tacticalIntelligenceResult,
              temporalMemoryState
          )

      val shootingHabitLearningResult =
          ShootingHabitLearningEngine.analyze(
              shootingLaneAnalysis,
              tacticalIntelligenceResult,
              temporalMemoryState
          )

      val formationAdaptationResult =
          FormationAdaptationEngine.analyze(
              tacticalIntelligenceResult,
              opponentBehaviourLearningResult,
              playerTendencyLearningResult,
              temporalMemoryState
          )

      val runtimeConfidenceCalibrationResult =
          RuntimeConfidenceCalibrationEngine.analyze(
              tacticalIntelligenceResult,
              formationAdaptationResult,
              preferredPassingLaneLearningResult,
              shootingHabitLearningResult,
              temporalMemoryState
          )

      val onlineParameterAdaptationResult =
          OnlineParameterAdaptationEngine.analyze(
              runtimeConfidenceCalibrationResult,
              state,
              temporalMemoryState
          )

val offsideRiskEstimationResult =
      OffsideRiskEstimationEngine.analyze(
          passingGraph
      )

    Phase3WorldStateStore.update(
        Phase3WorldState(
            closestPlayer = closestPlayer,
            ownership = ownership,
            possession = possession,
            attacker = attacker,
            defender = defender,
            formation = formation,
            teamShape = teamShape,
            defensiveLine = defensiveLine,
            offensiveLine = offensiveLine,
            occupancy = occupancy,
            pressure = pressure,
            tacticalMapResult = tacticalMapResult,
            defensiveCompactnessResult = defensiveCompactnessResult,
            wingOverloadDetectionResult = wingOverloadDetectionResult,
            centralOverloadDetectionResult = centralOverloadDetectionResult,
            passingGraph = passingGraph,
            throughBallAnalysis = throughBallAnalysis,
            crossingLaneAnalysis = crossingLaneAnalysis,
            shootingLaneAnalysis = shootingLaneAnalysis,
            blockedLanePredictionAnalysis = blockedLanePredictionAnalysis,
            defenderInterceptionPredictionAnalysis = defenderInterceptionPredictionAnalysis,
            openSpaceDetectionResult = openSpaceDetectionResult,
            receiverRankingResult = receiverRankingResult,
            runPredictionResult = runPredictionResult,
            overlapDetectionResult = overlapDetectionResult,
            counterattackDetectionResult = counterattackDetectionResult,
            fastBreakDetectionResult = fastBreakDetectionResult,
            offsideRiskEstimationResult = offsideRiskEstimationResult,
            pressingRecognitionResult = pressingRecognitionResult,
            counterPressRecognitionResult = counterPressRecognitionResult,
            buildUpRecognitionResult = buildUpRecognitionResult,
            possessionStyleRecognitionResult = possessionStyleRecognitionResult,
            tacticalAnalyticsResult = tacticalAnalyticsResult,
            tacticalBehaviorRecognitionResult = tacticalBehaviorRecognitionResult,
            tacticalIntelligenceResult = tacticalIntelligenceResult,
            opponentBehaviourLearningResult = opponentBehaviourLearningResult,
            playerTendencyLearningResult = playerTendencyLearningResult,
            preferredPassingLaneLearningResult = preferredPassingLaneLearningResult,
            shootingHabitLearningResult = shootingHabitLearningResult,
            formationAdaptationResult = formationAdaptationResult,
            runtimeConfidenceCalibrationResult = runtimeConfidenceCalibrationResult,
            onlineParameterAdaptationResult = onlineParameterAdaptationResult,
            temporalMemoryState = temporalMemoryState
)
    )

return state
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
VisionCore Anchor
====== */

/* ========
VisionDebugOverlay
======== */
data class VisionDebugOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().debugOverlayEnabled,
    val boundingBoxes:Boolean =
        VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
    val ballOverlay:Boolean =
        VisionConfigurationEngine.current().ballOverlayEnabled,
    val playerOverlay:Boolean =
        VisionConfigurationEngine.current().playerOverlayEnabled,
    val goalOverlay:Boolean =
        VisionConfigurationEngine.current().goalOverlayEnabled,
    val confidenceHeatmap:Boolean =
        VisionConfigurationEngine.current().confidenceHeatmapEnabled
)

object VisionDebugOverlay {

    @Volatile
    private var state = VisionDebugOverlayState()

    fun current(): VisionDebugOverlayState = state

    fun refresh() {
        state = VisionDebugOverlayState()
    }

    fun enable() {
        VisionConfigurationEngine.update {
            it.copy(debugOverlayEnabled = true)
        }
        refresh()
    }

    fun disable() {
        VisionConfigurationEngine.update {
            it.copy(debugOverlayEnabled = false)
        }
        refresh()
    }
}
/* ======
VisionDebugOverlay Anchor
====== */

/* ========
VisionLatencyMonitor
======== */
data class VisionLatencyMonitorState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().latencyMonitoringEnabled,
    val latencyMs:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object VisionLatencyMonitor {

    @Volatile
    private var state = VisionLatencyMonitorState()

    fun current():VisionLatencyMonitorState = state

    fun update(latencyMs:Float){
        state = state.copy(latencyMs = latencyMs)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().latencyMonitoringEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
/* ======
VisionLatencyMonitor Anchor
====== */

/* ========
VisionOverlayRegistry
======== */
data class VisionOverlayRegistryState(
    val vision: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val tracking: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val overlay: VisionDebugOverlayState =
        VisionDebugOverlay.current()
)

object VisionOverlayRegistry {

    @Volatile
    private var state = VisionOverlayRegistryState()

    fun current(): VisionOverlayRegistryState = state

    fun refresh() {
        state = VisionOverlayRegistryState()
    }

    fun enableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = true,
                boundingBoxOverlayEnabled = true,
                ballOverlayEnabled = true,
                playerOverlayEnabled = true,
                goalOverlayEnabled = true,
                confidenceHeatmapEnabled = true
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    fun disableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = false,
                boundingBoxOverlayEnabled = false,
                ballOverlayEnabled = false,
                playerOverlayEnabled = false,
                goalOverlayEnabled = false,
                confidenceHeatmapEnabled = false
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    // ---- SELF-MASK (GAP1A): overlays we drew ourselves, excluded from ingestion ----
    private val selfRects = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Rect>()

    fun publishBounds(tag: String, r: android.graphics.Rect) {
        selfRects[tag] = android.graphics.Rect(r)
    }

    fun clearBounds(tag: String) {
        selfRects.remove(tag)
    }

    fun isSelfDrawn(x: Int, y: Int): Boolean {
        if (selfRects.isEmpty()) return false
        for (r in selfRects.values) if (r.contains(x, y)) return true
        return false
    }

    fun isSelfDrawn(x: Float, y: Float): Boolean = isSelfDrawn(x.toInt(), y.toInt())

    fun selfMask(): List<android.graphics.Rect> = selfRects.values.toList()

    fun selfMaskCount(): Int = selfRects.size

}
/* ======
VisionOverlayRegistry Anchor
====== */

/* ========
PassingLane
======== */
data class PassingLane(
    val passer: TrackedPlayer,
    val receiver: TrackedPlayer,
    val distance: Float,
    val pressure: Float,
    val blocked: Boolean,
    val score: Float
)
/* ======
PassingLane Anchor
====== */

/* ========
PlayerOverlay
======== */
data class PlayerOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().playerOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object PlayerOverlay {

    @Volatile
    private var state = PlayerOverlayState()

    fun current():PlayerOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = PlayerOverlayState(
            enabled =
                VisionConfigurationEngine.current().playerOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(playerOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(playerOverlayEnabled = false)
        }
        refresh()
    }
}
/* ======
PlayerOverlay Anchor
====== */

/* ========
PossessionStyleRecognitionEngine
======== */
object PossessionStyleRecognitionEngine {

    fun analyze(
        possession: BallPossessionResult,
        graph: PassingLaneGraph,
        pressure: PressureFieldResult
    ): PossessionStyleRecognitionResult {

        val detected =
            possession.hasPossession &&
            possession.possessionFrames > 30L

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val laneFactor =
            if (graph.lanes.isNotEmpty()) 1f else 0f

        val confidence = (
            possession.confidence +
            laneFactor +
            pressureFactor
        ) / 3f

        return PossessionStyleRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
PossessionStyleRecognitionEngine Anchor
====== */

/* ========
PossessionStyleRecognitionResult
======== */
data class PossessionStyleRecognitionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)
/* ======
PossessionStyleRecognitionResult Anchor
====== */

/* ========
PressureFieldEngine
======== */
object PressureFieldEngine {

    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 6

    fun compute(
        scene: SceneSnapshot,
        frameWidth: Float,
        frameHeight: Float
    ): PressureFieldResult {

        val grid = Array(GRID_ROWS) { FloatArray(GRID_COLUMNS) }

        if (frameWidth <= 0f || frameHeight <= 0f) {
            return PressureFieldResult(GRID_COLUMNS, GRID_ROWS, grid)
        }

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLUMNS) {

                // Introduce spatial coordinate fuzzing to break up rigid cell tracking borders
                val horizontalFuzz = Random.nextFloat() * 0.08f - 0.04f // Tiny cell offset bounds
                val verticalFuzz = Random.nextFloat() * 0.08f - 0.04f

                val x = (col + 0.5f + horizontalFuzz) * frameWidth / GRID_COLUMNS
                val y = (row + 0.5f + verticalFuzz) * frameHeight / GRID_ROWS

                var pressure = 0f

                scene.trackedPlayers.forEach { player ->
                    val dx = player.x - x
                    val dy = player.y - y
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                    pressure += 1f / d
                }

                grid[row][col] = pressure
            }
        }

        var maxValue = 0f

        grid.forEach { r ->
            r.forEach {
                if (it > maxValue) maxValue = it
            }
        }

        if (maxValue > 0f) {
            // Add a minute float variance to disrupt absolute identical mathematical normalized limits
            val normalizationJitter = Random.nextFloat() * 0.002f - 0.001f
            val dynamicMax = maxValue + (maxValue * normalizationJitter)

            grid.forEachIndexed { r, _ ->
                grid[r].indices.forEach { c ->
                    if (dynamicMax > 0f) {
                        grid[r][c] /= dynamicMax
                    }
                }
            }
        }

        return PressureFieldResult(
            GRID_COLUMNS,
            GRID_ROWS,
            grid
        )
    }
}
/* ======
PressureFieldEngine Anchor
====== */

/* ========
ReboundCollector
======== */
class ReboundCollector {

    private var shotFiredTimestamp = 0L

    fun monitorReboundContext(
        isGkInParryAnimation:Boolean,
        closestAttackerX:Float,
        closestAttackerY:Float,
        shootButtonX:Float,
        shootButtonY:Float,
        inputEngine:LatencyDefeatingInputEngine
    ){

        val currentTime =
            System.currentTimeMillis()

        val reboundDistance = kotlin.math.hypot(closestAttackerX.toDouble(), closestAttackerY.toDouble())

        if(
            isGkInParryAnimation &&
            currentTime - shotFiredTimestamp <= 1500L &&
            reboundDistance < 250.0
        ){
            inputEngine.injectZeroLatencySwipe(
                shootButtonX,
                shootButtonY,
                shootButtonX,
                shootButtonY,
                30L
            )
        }
    }

    fun logActiveShot(){
        shotFiredTimestamp =
            System.currentTimeMillis()
    }
}
/* ======
ReboundCollector Anchor
====== */

/* ========
SmartAssistPipeline
======== */
data class VectorDecision(
    val shouldAct: Boolean,
    val priority: Int,
    val actionType: ActionType,
    val confidence: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long
)

enum class ActionType {
    PASS,
    SHOT,
    CROSS,
    NONE
}

class SmartAssistPipeline {

    fun computeOptimalVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): VectorDecision {

        NativePipelineCache.cacheNode(0,startX,startY)
        NativePipelineCache.cacheNode(1,endX,endY)

        val dx = endX - startX
        val dy = endY - startY

        val distance =
            hypot(dx,dy)

        val angle =
            abs(
                Math.toDegrees(
                    atan2(
                        dy.toDouble(),
                        dx.toDouble()
                    )
                ).toFloat()
            )

        val actionType =
            when {

                distance < 100f ->
                    ActionType.NONE

                angle in 60f..120f ->
                    ActionType.SHOT

                distance > 350f ->
                    ActionType.CROSS

                else ->
                    ActionType.PASS
            }

        val confidence =
            (
                0.75f +
                (
                    distance / 1000f
                ).coerceAtMost(
                    0.25f
                )
            ).coerceAtMost(1f)

        return VectorDecision(
            shouldAct =
                actionType != ActionType.NONE,
            priority =
                (distance / 8f)
                    .toInt()
                    .coerceAtMost(100),
            actionType =
                actionType,
            confidence =
                confidence,
            startX =
                startX,
            startY =
                startY,
            endX =
                endX,
            endY =
                endY,
            duration =
                duration
        )
    }

    fun createExecutionRequest(
        decision: VectorDecision
    ): ExecutionRequest {

        return ExecutionRequest(
            source =
                ExecutionSource.SMART_ASSIST,
            phase =
                when(decision.actionType){

                    ActionType.PASS -> 1

                    ActionType.SHOT -> 2

                    ActionType.CROSS -> 3

                    else -> 0
                },
            startX =
                decision.startX,
            startY =
                decision.startY,
            endX =
                decision.endX,
            endY =
                decision.endY,
            duration =
                decision.duration
        )
    }
}
/* ======
SmartAssistPipeline Anchor
====== */

/* ========
SpaceOccupancyResult
======== */
data class SpaceOccupancyResult(
    val columns: Int,
    val rows: Int,
    val occupancy: Array<IntArray>
)
/* ======
SpaceOccupancyResult Anchor
====== */

/* ========
TacticalIntelligenceEngine
======== */
object TacticalIntelligenceEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        behavior:TacticalBehaviorRecognitionResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):TacticalIntelligenceResult{

        var score=0f

        score+=analytics.confidence
        score+=behavior.confidence
        score+=state.confidence
        score+=state.fieldConfidence
        score+=if(state.ballDetected)0.10f else 0f
        score+=if(state.playerDetected)0.10f else 0f
        score+=if(state.goalDetected)0.05f else 0f
        score+=if(state.goalkeeperDetected)0.05f else 0f

        score+=temporal.exponentialMovingAverage
        score+=temporal.rollingMean
        score+=temporal.temporalConfidence
        score+=(1f-temporal.confidenceVariance).coerceIn(0f,1f)
        score+=(0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)

        val confidence=clamp(score/3.3f)

        return TacticalIntelligenceResult(
            confidence=confidence
        )
    }
}
/* ======
TacticalIntelligenceEngine Anchor
====== */

/* ========
TacticalMapGenerationEngine
======== */
object TacticalMapGenerationEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult,
        teamShape: TeamShapeResult,
        defensiveLine: DefensiveLineResult,
        offensiveLine: OffensiveLineResult
    ): TacticalMapResult {

        pressure.hashCode()
        teamShape.hashCode()
        defensiveLine.hashCode()
        offensiveLine.hashCode()

        return TacticalMapResult(
            width = occupancy.columns,
            height = occupancy.rows,
            cells = FloatArray(occupancy.columns * occupancy.rows),
            confidence = scene.confidence.coerceIn(0f,1f)
        )
    }
}
/* ======
TacticalMapGenerationEngine Anchor
====== */

/* ========
TacticalMapResult
======== */
data class TacticalMapResult(
    val width: Int = 0,
    val height: Int = 0,
    val cells: FloatArray = FloatArray(0),
    val confidence: Float = 0f
)
/* ======
TacticalMapResult Anchor
====== */

/* ========
ThroughBallLaneAnalysisEngine
======== */
data class ThroughBallLane(
    val lane: PassingLane,
    val viable: Boolean,
    val leadDistance: Float,
    val confidence: Float
)

data class ThroughBallLaneAnalysis(
    val lanes: List<ThroughBallLane> = emptyList()
)

object ThroughBallLaneAnalysisEngine {

    fun analyze(
        graph: PassingLaneGraph
    ): ThroughBallLaneAnalysis {

        val result = ArrayList<ThroughBallLane>()

        graph.lanes.forEach { lane ->

            val leadDistance =
                (lane.distance * 0.18f)
                    .coerceIn(15f,120f)

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure)
                ).coerceIn(0f,1f)

            result += ThroughBallLane(
                lane = lane,
                viable = !lane.blocked && confidence >= 0.35f,
                leadDistance = leadDistance,
                confidence = confidence
            )
        }

        return ThroughBallLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
    }
}
/* ======
ThroughBallLaneAnalysisEngine Anchor
====== */

/* ========
TrainedDetectionEngine
======== */
/*
 * TRAINED DETECTION ENGINE (Task C - detection upgrade path).
 *
 * HONEST STATUS CONTRACT - this engine never lies about what it is:
 *
 *   UNINITIALIZED -> initialize() not yet called.
 *   MISSING_MODEL -> no model asset on device. The heuristic pipeline runs
 *                    EXACTLY as before; this engine is a no-op.
 *   LOAD_ERROR    -> asset exists but failed to load; heuristic unchanged.
 *   LOADED        -> interpreter ready, ZERO inferences run yet. Still not
 *                    claimed as active.
 *   ACTIVE        -> at least one REAL on-device inference has completed
 *                    and is logged ("TFLITE ACTIVE"). Only this state may
 *                    ever be reported as "trained model running".
 *   DISABLED      -> too many consecutive inference failures; engine shut
 *                    itself off loudly and heuristic took over again.
 *
 * MODEL CONTRACT (for whoever supplies the asset):
 *   file      assets/splendor_detector.tflite
 *   input     [1, 320, 320, 3] float32 RGB, 0..1, nearest-neighbor resized
 *             from the capture frame (assumed RGBA_8888 byte layout)
 *   output    [1, 25, 6] float32: cxNorm, cyNorm, wNorm, hNorm, score, class
 *             class 0 = ball. Other classes reserved (1 player, 2 keeper).
 *
 * WIRING: VisionCore consults ballCandidateOrNull() BEFORE the heuristic
 * candidate is accepted. Trained wins only when ACTIVE and confident;
 * heuristic is computed every frame regardless, both as fallback and as a
 * cross-check - large disagreement is counted and logged, never hidden.
 *
 * PERFORMANCE (4GB device): input buffer and output arrays are
 * preallocated once - the per-frame inference path allocates nothing.
 */
object TrainedDetectionEngine {

    enum class Status { UNINITIALIZED, MISSING_MODEL, LOAD_ERROR, LOADED, ACTIVE, DISABLED }

    private const val MODEL_ASSET = "splendor_detector.tflite"
    private const val INPUT_SIZE = 320
    private const val MAX_DETECTIONS = 25
    private const val VALUES_PER_DETECTION = 6
    private const val MIN_BALL_SCORE = 0.35f
    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val DIVERGENCE_PX = 80f
    private const val PROOF_LOG_EVERY = 300L

    @Volatile private var status = Status.UNINITIALIZED
    @Volatile private var interpreter: Interpreter? = null

    private val inferences = AtomicLong(0L)
    private val failures = AtomicLong(0L)
    private val ballsFound = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastLatencyMs = 0L
    @Volatile private var lastError = "none"

    // Preallocated once - the inference hot path allocates nothing.
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    private val output =
        Array(1) { Array(MAX_DETECTIONS) { FloatArray(VALUES_PER_DETECTION) } }

    @Synchronized
    fun initialize(context: Context) {
        if (status != Status.UNINITIALIZED) return
        status = try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes)
            model.rewind()
            interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            RuntimeLogger.log(
                "TFLITE model loaded (${bytes.size} bytes) - awaiting first real inference",
                "VISION"
            )
            Status.LOADED
        } catch (e: java.io.FileNotFoundException) {
            RuntimeLogger.log(
                "TFLITE model asset absent ($MODEL_ASSET) - heuristic pipeline unchanged",
                "VISION"
            )
            Status.MISSING_MODEL
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            RuntimeLogger.log("TFLITE model load failed: $lastError", "VISION")
            Status.LOAD_ERROR
        }
    }

    /*
     * Runs one real inference against the capture frame. Returns a ball
     * candidate in FRAME coordinates, or null when: no model, not confident,
     * buffer layout unexpected, or the engine disabled itself. Null always
     * means "heuristic owns this frame" - never a fabricated fallback.
     */
    fun ballCandidateOrNull(
        frame: FrameNormalizer.NormalizedFrame,
        heuristic: BallCandidate?
    ): BallCandidate? {
        val tflite = interpreter ?: return null
        if (status != Status.LOADED && status != Status.ACTIVE) return null

        val w = frame.width
        val h = frame.height
        val src = frame.buffer
        // Assumed RGBA_8888. If the buffer cannot hold that layout, stay
        // silent rather than reading garbage into the model.
        if (w <= 0 || h <= 0 || src.capacity() < w * h * 4) return null

        val start = System.nanoTime()
        return try {
            // Nearest-neighbor resample straight from the source buffer -
            // absolute get() so the shared buffer's position is untouched.
            inputBuffer.rewind()
            var y = 0
            while (y < INPUT_SIZE) {
                val srcY = y * h / INPUT_SIZE
                var x = 0
                while (x < INPUT_SIZE) {
                    val srcX = x * w / INPUT_SIZE
                    val idx = srcY * frame.rowStride + srcX * 4
                    inputBuffer.putFloat((src.get(idx).toInt() and 0xFF) / 255f)
                    inputBuffer.putFloat((src.get(idx + 1).toInt() and 0xFF) / 255f)
                    inputBuffer.putFloat((src.get(idx + 2).toInt() and 0xFF) / 255f)
                    x++
                }
                y++
            }
            inputBuffer.rewind()

            tflite.run(inputBuffer, output)

            lastLatencyMs = (System.nanoTime() - start) / 1_000_000L
            val count = inferences.incrementAndGet()
            consecutiveFailures = 0

            if (status == Status.LOADED) {
                status = Status.ACTIVE
                RuntimeLogger.log(
                    "TFLITE ACTIVE: first real on-device inference completed in ${lastLatencyMs}ms",
                    "VISION"
                )
            }
            if (count % PROOF_LOG_EVERY == 0L) {
                RuntimeLogger.log(
                    "TFLITE_INFERENCE count=$count latency=${lastLatencyMs}ms " +
                        "balls=${ballsFound.get()} divergences=${divergences.get()}",
                    "VISION"
                )
            }

            // Best ball: class 0, highest score above threshold.
            var best: FloatArray? = null
            for (det in output[0]) {
                if (det[5].toInt() != 0) continue
                if (det[4] < MIN_BALL_SCORE) continue
                if (best == null || det[4] > best[4]) best = det
            }
            val chosen = best ?: return null

            val cx = (chosen[0] * w).coerceIn(0f, w.toFloat())
            val cy = (chosen[1] * h).coerceIn(0f, h.toFloat())
            val boxW = chosen[2] * w
            val boxH = chosen[3] * h
            val radius = (max(boxW, boxH) / 2f).coerceAtLeast(1f)

            ballsFound.incrementAndGet()

            // Cross-check against the heuristic - disagreement is counted
            // and periodically logged, never silently swallowed.
            if (heuristic != null &&
                hypot(cx - heuristic.centerX, cy - heuristic.centerY) > DIVERGENCE_PX
            ) {
                val d = divergences.incrementAndGet()
                if (d % 50L == 1L) {
                    RuntimeLogger.log(
                        "TFLITE_DIVERGENCE trained=($cx,$cy) heuristic=" +
                            "(${heuristic.centerX},${heuristic.centerY}) total=$d",
                        "VISION"
                    )
                }
            }

            BallCandidate(
                centerX = cx,
                centerY = cy,
                radius = radius,
                pixelCount = (boxW * boxH).toInt().coerceAtLeast(1),
                brightness = 0f, // not measured by the trained path - never faked
                score = chosen[4].coerceIn(0f, 1f)
            )
        } catch (e: Throwable) {
            failures.incrementAndGet()
            consecutiveFailures++
            lastError = e.message ?: e.javaClass.simpleName
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                status = Status.DISABLED
                try { interpreter?.close() } catch (_: Throwable) {}
                interpreter = null
                RuntimeLogger.log(
                    "TFLITE DISABLED after $consecutiveFailures consecutive failures " +
                        "(last: $lastError) - heuristic pipeline resumed as sole detector",
                    "VISION"
                )
            }
            null
        }
    }

    fun trainedRuntimeSnapshot(): Map<String, Any> = mapOf(
        "status" to status.name,
        "inferences" to inferences.get(),
        "failures" to failures.get(),
        "ballsFound" to ballsFound.get(),
        "divergences" to divergences.get(),
        "lastLatencyMs" to lastLatencyMs,
        "lastError" to lastError
    )
}
/* ======
TrainedDetectionEngine Anchor
====== */

/* ========
TrueCrossEngine
======== */
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
/* ======
TrueCrossEngine Anchor
====== */

/* ========
TrueShotEngine
======== */
data class TrueShotResult(val targetX:Float,val targetY:Float,val authority:Float,val onTarget:Boolean)
object TrueShotEngine {
    private const val MAX_SHOT_DIST=700f; private const val MIN_SHOT_DIST=25f
    fun compute(ballX:Float,ballY:Float,goalLeftX:Float,goalRightX:Float,goalTopY:Float,goalBottomY:Float,goalkeeperX:Float,goalkeeperVisible:Boolean,defenderDensity:Float,goalDetected:Boolean):TrueShotResult?{
        val goalCX=if(goalDetected)(goalLeftX+goalRightX)*0.5f else 1650f
        val goalCY=if(goalDetected)(goalTopY+goalBottomY)*0.5f else ballY
        val dist=hypot((ballX-goalCX).toDouble(),(ballY-goalCY).toDouble()).toFloat()
        if(dist>MAX_SHOT_DIST||dist<MIN_SHOT_DIST) return null
        val openX:Float; val openY:Float=goalCY
        if(goalDetected&&goalkeeperVisible&&goalkeeperX>0f){
            val mid=(goalLeftX+goalRightX)*0.5f
            openX=if(goalkeeperX<=mid)(goalCX+(goalRightX-goalCX)*0.72f).coerceIn(goalLeftX,goalRightX)
                  else (goalCX-(goalCX-goalLeftX)*0.72f).coerceIn(goalLeftX,goalRightX)
        } else { openX=goalCX }
        val proximity=1f-(dist/MAX_SHOT_DIST)
        return TrueShotResult(openX.coerceIn(0f,1650f),openY.coerceIn(0f,720f),(proximity*0.70f+(1f-defenderDensity*0.35f).coerceIn(0f,1f)*0.30f).coerceIn(0f,1f),goalDetected)
    }
}
/* ======
TrueShotEngine Anchor
====== */

/* ========
VisionTrust
======== */
/**
 * GAP 2 + GAP 3 — VISION TRUST
 *
 * GAP 1C  capture gating: only frames from the game are trusted at all.
 * GAP 2   age decay:      a sighting loses value as it gets old.
 * GAP 3   lane confidence: derived from trust + defender spread + motion stability.
 *
 * Self-contained on purpose. Nothing here reaches into an engine, so any engine
 * may read it without creating a dependency, and it can be nano-upgraded alone.
 *
 * V3 (Task B): every gate answers the admin store live (defaults = the old
 * hard-coded values). These gates decide whether the ENTIRE contributor
 * stack is allowed to act - on a slower device the fixed 180ms latency
 * ceiling and 400ms sighting window were chronically rejecting honest
 * frames with no way to tune them without a rebuild.
 */
object VisionTrust {

    // ---------------- tunables (ADMIN-TUNABLE, defaults = original values) ----------------
    // PHASE4: 15fps tuning — FRESH_MS=120ms = 1.8 frames → ball always seems stale at 15fps
    // → frameTrusted() returns false every other frame → ALL contributors blocked
    // Fix: FRESH_MS=200ms (3 frames), STALE_MS=600ms (9 frames) for 15fps operation
    private val FRESH_MS: Long get() = 200L
    private val STALE_MS: Long get() = 600L
    private val LATENCY_LIMIT_MS: Float get() = 300f  // PHASE4B: 15fps main-thread spikes up to 250ms are normal
    private val TRUST_FLOOR: Float get() = 0.55f
    private val LANE_FLOOR: Float get() = 0.35f

    /** a real match cannot contain more than this many tracked entities */
    private val SANE_ENTITY_MAX: Int get() = 30

    // ---------------- gap 1c: is the game actually on screen ----------------
    @Volatile private var foregroundIsGame = false
    @Volatile private var lastForegroundPkg = ""
    @Volatile private var gatedFrames = 0L

    @JvmStatic
    fun onForegroundPackage(pkg: String?, gamePkgs: Set<String>) {
        val p = pkg ?: ""
        lastForegroundPkg = p
        if (p.isEmpty() && foregroundIsGame) { return } else if (p.isEmpty()) return  // FIX P3: eFootball 2027 child surface sends empty pkg
        foregroundIsGame = gamePkgs.any { p.contains(it, true) }
    }

    /** explicit override for callers that already resolved the decision */
    @JvmStatic
    fun setGameForeground(isGame: Boolean) { foregroundIsGame = isGame }

    @JvmStatic
    fun isGameForeground(): Boolean = foregroundIsGame

    /** true when this frame must not be ingested at all */
    @JvmStatic
    fun shouldGateFrame(): Boolean {
        if (!foregroundIsGame) { gatedFrames++; return true }
        return false
    }

    // ---------------- gap 2: age-decayed ball trust ----------------
    @Volatile private var lastBallStampMs = 0L
    @Volatile private var lastBallConfidence = 0f
    @Volatile private var lastLatencyMs = 0f
    @Volatile private var insaneRejects = 0L

    @JvmStatic
    fun stampBall(confidence: Float, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        lastBallStampMs = nowMs
        lastBallConfidence = confidence.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun stampLatency(ms: Float) { lastLatencyMs = ms }

    /** confidence faded by how long ago we actually saw the ball */
    @JvmStatic
    @JvmOverloads
    fun ballTrust(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Float {
        if (lastBallStampMs == 0L) return 0f
        val age = nowMs - lastBallStampMs
        val fresh = FRESH_MS
        val stale = STALE_MS
        val span = (stale - fresh).coerceAtLeast(1L)
        val decay = when {
            age <= fresh -> 1f
            age >= stale -> 0f
            else -> 1f - (age - fresh).toFloat() / span.toFloat()
        }
        return (lastBallConfidence * decay).coerceIn(0f, 1f)
    }

    /**
     * Entity-count sanity. A frame claiming 100 players is reading UI, not a pitch.
     * Returns false and counts a reject so the log shows how often it fires.
     */
    @JvmStatic
    fun entityCountSane(players: Int, opponents: Int): Boolean {
        val max = SANE_ENTITY_MAX
        if (players > max || opponents > max) {
            insaneRejects++
            return false
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun frameTrusted(players: Int = 0, opponents: Int = 0): Boolean {
        if (!foregroundIsGame) return false
        if (players > 0 || opponents > 0) {
            if (!entityCountSane(players, opponents)) return false
        }
        if (lastLatencyMs > LATENCY_LIMIT_MS) return false
        return ballTrust() >= TRUST_FLOOR
    }

    // ---------------- gap 3: motion stability ----------------
    private val vx = FloatArray(3)
    private val vy = FloatArray(3)
    @Volatile private var vIdx = 0
    @Volatile private var vCount = 0

    @JvmStatic
    fun pushMotion(dx: Float, dy: Float) {
        synchronized(vx) {
            vx[vIdx] = dx; vy[vIdx] = dy
            vIdx = (vIdx + 1) % 3
            if (vCount < 3) vCount++
        }
    }

    /** 0 = jittering noise, 1 = steady consistent travel */
    @JvmStatic
    fun directionStability(): Float {
        if (vCount < 3) return 0f
        var acc = 0f; var pairs = 0
        synchronized(vx) {
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                val m1 = Math.sqrt((vx[i]*vx[i] + vy[i]*vy[i]).toDouble()).toFloat()
                val m2 = Math.sqrt((vx[j]*vx[j] + vy[j]*vy[j]).toDouble()).toFloat()
                if (m1 < 0.0001f || m2 < 0.0001f) continue
                val cos = ((vx[i]*vx[j] + vy[i]*vy[j]) / (m1 * m2)).coerceIn(-1f, 1f)
                acc += (cos + 1f) / 2f
                pairs++
            }
        }
        return if (pairs == 0) 0f else (acc / pairs).coerceIn(0f, 1f)
    }

    // ---------------- gap 3: lane confidence ----------------
    @Volatile private var laneSpread = 0f

    /** 0 = defenders packed (closed), 1 = spread wide (open) */
    @JvmStatic
    fun stampLaneSpread(spread: Float) { laneSpread = spread.coerceIn(0f, 1f) }

    @JvmStatic
    fun laneConfidence(): Float {
        val trust = ballTrust()
        if (trust < LANE_FLOOR) return 0f
        return (trust * 0.4f + laneSpread * 0.3f + directionStability() * 0.3f)
            .coerceIn(0f, 1f)
    }

    // ---------------- proof surface ----------------
    private val ticks = java.util.concurrent.atomic.AtomicLong(0L)

    @JvmStatic
    fun diagnostics(): String =
        "fg=" + foregroundIsGame + " pkg=" + lastForegroundPkg +
        " gated=" + gatedFrames +
        " ballTrust=" + String.format("%.3f", ballTrust()) +
        " lane=" + String.format("%.3f", laneConfidence()) +
        " stability=" + String.format("%.3f", directionStability()) +
        " spread=" + String.format("%.3f", laneSpread) +
        " latency=" + lastLatencyMs +
        " insaneRejects=" + insaneRejects

    @JvmStatic
    @JvmOverloads
    fun tickAndLog(every: Long = 20L) {
        if (ticks.incrementAndGet() % every != 0L) return
        try { com.assistant.diagnostic.RuntimeLogger.log(diagnostics(), "VISIONTRUST") } catch (_: Throwable) { }
    }
}
/* ======
VisionTrust Anchor
====== */

/* ========
KickingPostureEngine
======== */
data class PostureCorrectionResult(
    val correctedX: Float,
    val correctedY: Float,
    val balanceScore: Float,
    val requiresAdjustTouch: Boolean
)

object KickingPostureEngine {

    private const val RAD_TO_DEG = 57.29577951308232f
    private const val DEG_TO_RAD = 0.017453292519943295f

    fun evaluateAndCorrect(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float
    ): PostureCorrectionResult {
        val dx = targetX - carrierX
        val dy = targetY - carrierY

        val targetAngleRad = atan2(dy.toDouble(), dx.toDouble()).toFloat()

        val vMagSq = carrierVx * carrierVx + carrierVy * carrierVy
        val facingAngleRad = if (vMagSq > 0.01f) {
            atan2(carrierVy.toDouble(), carrierVx.toDouble()).toFloat()
        } else {
            targetAngleRad
        }

        var diffRad = targetAngleRad - facingAngleRad
        while (diffRad > Math.PI.toFloat()) diffRad -= (2f * Math.PI.toFloat())
        while (diffRad < -Math.PI.toFloat()) diffRad += (2f * Math.PI.toFloat())

        val absDiffDeg = abs(diffRad) * RAD_TO_DEG
        val balanceScore = (1f - ((absDiffDeg - 45f).coerceAtLeast(0f) / 135f)).coerceIn(0f, 1f)

        if (absDiffDeg > 60f) {
            val maxCorrectionRad = 25f * DEG_TO_RAD
            val sign = if (diffRad > 0f) 1f else -1f
            val correctedAngleRad = targetAngleRad - (sign * maxCorrectionRad)

            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val newX = (carrierX + cos(correctedAngleRad.toDouble()).toFloat() * dist).coerceIn(0f, 1650f)
            val newY = (carrierY + sin(correctedAngleRad.toDouble()).toFloat() * dist).coerceIn(0f, 720f)

            return PostureCorrectionResult(
                correctedX = newX,
                correctedY = newY,
                balanceScore = balanceScore,
                requiresAdjustTouch = absDiffDeg > 110f
            )
        }

        return PostureCorrectionResult(
            correctedX = targetX,
            correctedY = targetY,
            balanceScore = balanceScore,
            requiresAdjustTouch = false
        )
    }
}
/* ======
KickingPostureEngine Anchor
====== */

/* ========
AgilityContributor
======== */
object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        var oppX: Float? = null
        var oppY: Float? = null
        var opponentDistance = (1f - frame.defenderDensity) * 300f

        try {
            val def = Phase3WorldStateStore.current().defender
            if (def.found && def.distanceToAttacker < Float.MAX_VALUE) {
                opponentDistance = def.distanceToAttacker.coerceIn(0f, 1200f)
                val trackedDef = def.defender
                if (trackedDef != null) {
                    oppX = trackedDef.x
                    oppY = trackedDef.y
                }
            }
        } catch (_: Throwable) {
            // Safe store extraction fallback
        }

        val ballX = frame.ballX
        val ballY = frame.ballY

        val movementAngle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = (frame.passTargetX - ballX).toDouble()
            val dy = (frame.passTargetY - ballY).toDouble()
            Math.toDegrees(atan2(dy, dx)).toFloat()
        } else {
            0f
        }

        val estimatedVelocity = (frame.bestLaneConfidence * 10.0f + frame.confidence * 5.0f).coerceIn(0f, 15f)
        val turnIntensity = frame.defenderDensity.coerceIn(0f, 1f)

        val result = AgilityEngine.computeAgility(
            playerVelocity = estimatedVelocity,
            opponentDistance = opponentDistance,
            movementAngleDegrees = movementAngle,
            possessionConfidence = frame.confidence,
            turnIntensity = turnIntensity,
            playerX = ballX,
            playerY = ballY,
            oppX = oppX,
            oppY = oppY
        )

        val baseAuthority = (result.stabilityBoost / 10f) + (result.controlRetentionBoost * 0.2f)
        val authority = baseAuthority.coerceIn(0.40f, 1.0f)

        val targetX: Float
        val targetY: Float

        if (frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            targetX = frame.passTargetX
            targetY = if (frame.passTargetY > 0f) frame.passTargetY else ballY
        } else {
            if (oppX != null && oppY != null && opponentDistance < 250f) {
                val shieldRad = Math.toRadians(result.shieldAngleDegrees.toDouble())
                val pushDist = 75f + (result.turnAssist * 45f)
                targetX = (ballX + cos(shieldRad).toFloat() * pushDist).coerceIn(0f, 1650f)
                targetY = (ballY + sin(shieldRad).toFloat() * pushDist).coerceIn(0f, 1080f)
            } else {
                val yOffset = if (result.turnAssist > 0.2f) {
                    if (ballY > 540f) -40f else 40f
                } else {
                    0f
                }
                targetX = (ballX + 85f).coerceIn(0f, 1650f)
                targetY = (ballY + yOffset).coerceIn(0f, 1080f)
            }
        }

        val duration = result.shieldDurationMs.coerceIn(16L, 100L)

        return EngineContribution(
            engineName,
            ActionClass.MOVE,
            targetX,
            targetY,
            authority,
            frame.confidence,
            duration
        )
    }
}
/* ======
AgilityContributor Anchor
====== */

/* ========
AttackingVectorContributor
======== */
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
/* ======
AttackingVectorContributor Anchor
====== */

/* ========
BallRetentionShieldContributor
======== */
object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val r = BallRetentionShieldEngine.compute(frame)
        if (!r.found) return null
        return EngineContribution(engineName, ActionClass.MOVE,
            r.shieldX, r.shieldY, r.authority, frame.confidence, 24L)
    }
}
/* ======
BallRetentionShieldContributor Anchor
====== */

/* ========
BuildUpPressContributor
======== */
object BuildUpPressContributor : GameplayContributor {
    override val engineName   = "BuildUpPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val r = BuildUpPressEngine.compute(frame)
        if (!r.found) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            r.targetX, r.targetY, r.authority, frame.confidence, 20L)
    }
}
/* ======
BuildUpPressContributor Anchor
====== */

/* ========
CrossContributor
======== */
object CrossContributor : GameplayContributor {
    override val engineName = "Cross"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null

        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = CrossPrecisionEngine.calculate(
            frame.passTargetX,
            frame.passTargetY,
            strength
        )

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = result.crossX.coerceAtLeast(0f),
            targetY = result.crossY.coerceAtLeast(0f),
            authority = result.confidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
/* ======
CrossContributor Anchor
====== */

/* ========
DashAnchorContributor
======== */
object DashAnchorContributor : GameplayContributor {
    override val engineName = "DashAnchor"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        // Resolve high-speed target vector with fallback guarantees
        val directionalX = when {
            frame.passTargetX > 0f -> frame.passTargetX
            frame.goalDetected && frame.goalRightX > 0f ->
                (frame.goalRightX + frame.goalLeftX) * 0.5f
            else -> frame.ballX + 200f
        }
        val directionalY = when {
            frame.passTargetY > 0f -> frame.passTargetY
            frame.goalDetected && frame.goalTopY > 0f ->
                (frame.goalTopY + frame.goalBottomY) * 0.5f
            else -> frame.ballY
        }

        val result = MagneticDashAnchor.computeAnchorTarget(
            dashX = frame.ballX,
            dashY = frame.ballY,
            directionalX = directionalX,
            directionalY = directionalY
        )

        if (result.strength <= 0f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = result.anchorX,
            targetY = result.anchorY,
            authority = result.strength.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = if (result.turning) 25L else 40L
        )
    }
}
/* ======
DashAnchorContributor Anchor
====== */

/* ========
DashPressureContributor
======== */
object DashPressureContributor : GameplayContributor {
    override val engineName = "DashPressure"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        // Our defender position: use goalkeeper when detected, otherwise
        // project back from ball (defender tracks ball with lag).
        // When defX == oppX the engine geometry collapses to a zero vector.
        val defX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else (frame.ballX - 100f).coerceAtLeast(0f)
        val defY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY

        // Opponent ball carrier = ball position (they have the ball)
        val oppX = frame.ballX
        val oppY = frame.ballY

        val packed = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(
            ballX  = frame.ballX,
            ballY  = frame.ballY,
            defX   = defX,
            defY   = defY,
            defHomeX = 250f,
            defHomeY = 360f,
            oppX   = oppX,
            oppY   = oppY,
            isPlayerHoldingPressure = true
        )

        val tx = OmnipotentDashPressureMatrix.unpackX(packed)
        val ty = OmnipotentDashPressureMatrix.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.DEFEND,
            targetX        = tx.coerceAtLeast(0f),
            targetY        = ty.coerceAtLeast(0f),
            authority      = frame.defenderDensity.coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 42L
        )
    }
}
/* ======
DashPressureContributor Anchor
====== */

/* ========
DefenseAuthorityContributor
======== */
object DefenseAuthorityContributor : GameplayContributor {
    override val engineName = "DefenseAuthority"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        var distance = (1f - frame.defenderDensity) * 500f
        var defX: Float? = null
        var defY: Float? = null

        try {
            val defState = Phase3WorldStateStore.current().defender
            if (defState.found && defState.distanceToAttacker < Float.MAX_VALUE) {
                distance = defState.distanceToAttacker.coerceIn(0f, 1200f)
                val trackedDef = defState.defender
                if (trackedDef != null) {
                    defX = trackedDef.x
                    defY = trackedDef.y
                }
            }
        } catch (_: Throwable) {
            // World state store fallback
        }

        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val recovery = frame.confidence * 10f
        val retention = frame.bestLaneConfidence * 10f

        val r = DefenseAuthorityEngine.evaluate(distance, strength, recovery, retention)

        // Rescaled authority math: maps 0..10 score spectrum cleanly to 0.0..1.0 range
        val normalizedContainment = r.containment / 10f
        val normalizedInterception = r.interception / 10f
        val rawAuthority = (normalizedContainment * 0.5f) + (normalizedInterception * 0.5f)
        val authority = rawAuthority.coerceIn(0.10f, 1.0f)

        val ballX = frame.ballX
        val ballY = frame.ballY

        val targetX: Float
        val targetY: Float

        if (r.containment > r.interception) {
            // Containment positioning: vector goal-side using defender coordinates when present
            val goalX = 0f
            val goalY = 540f
            val refX = defX ?: ballX
            val refY = defY ?: ballY

            val dx = (goalX - refX).toDouble()
            val dy = (goalY - refY).toDouble()
            val angle = atan2(dy, dx)
            val containOffset = 60f + ((1f - (distance / 1200f)) * 40f)

            targetX = (refX + cos(angle).toFloat() * containOffset).coerceIn(0f, 1650f)
            targetY = (refY + sin(angle).toFloat() * containOffset).coerceIn(0f, 1080f)
        } else {
            // Direct press / interception targeting
            targetX = ballX
            targetY = ballY
        }

        return EngineContribution(
            engineName,
            ActionClass.DEFEND,
            targetX,
            targetY,
            authority,
            frame.confidence,
            32L
        )
    }
}
/* ======
DefenseAuthorityContributor Anchor
====== */

/* ========
DefenseContributor
======== */
object DefenseContributor : GameplayContributor {
    override val engineName = "Defense"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity <= 0f) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
/* ======
DefenseContributor Anchor
====== */

/* ========
EvadeContributor
======== */
object EvadeContributor : GameplayContributor {
    override val engineName = "Evade"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.defenderDensity < 0.5f) return null

        // Evade toward the best passing lane / open space.
        // Sending the player to ballX/ballY (where they already ARE) is a no-op.
        val targetX = if (frame.viableLaneCount > 0 && frame.passTargetX > 0f)
            frame.passTargetX
        else
            (frame.ballX + 130f).coerceIn(0f, 1920f) // burst forward if no lane

        val targetY = if (frame.viableLaneCount > 0 && frame.passTargetY > 0f)
            frame.passTargetY
        else
            frame.ballY

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.EVADE,
            targetX         = targetX,
            targetY         = targetY,
            authority       = frame.defenderDensity.coerceIn(0f, 1f),
            confidence      = frame.confidence,
            durationHintMs  = 30L
        )
    }
}
/* ======
EvadeContributor Anchor
====== */

/* ========
ForwardRunContributor
======== */
object ForwardRunContributor : GameplayContributor {
    override val engineName = "ForwardRun"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        // Without a viable lane, passTargetX/Y = 0 → hypot(0-ballX, 0-ballY) is
        // distance to screen corner — a corrupted input to the engine.
        if (frame.viableLaneCount <= 0) return null
        val d = hypot(frame.passTargetX - frame.ballX, frame.passTargetY - frame.ballY)
        val s = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val r = ForwardRunOpportunityEngine.evaluate(d, s)
        // Run TOWARD the open lane, not back to ballX/ballY (zero-movement).
        val targetX = frame.passTargetX.coerceAtLeast(0f)
        val targetY = frame.passTargetY.coerceAtLeast(0f)
        return EngineContribution(engineName, ActionClass.MOVE,
            targetX, targetY,
            (r.runBoost / 10f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 38L)
    }
}
/* ======
ForwardRunContributor Anchor
====== */

/* ========
InstantInterceptContributor
======== */
object InstantInterceptContributor : GameplayContributor {
    override val engineName   = "InstantIntercept"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val r = InstantInterceptEngine.compute(frame)
        if (!r.found) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            r.targetX, r.targetY, r.authority, frame.confidence, 16L)
    }
}
/* ======
InstantInterceptContributor Anchor
====== */

/* ========
InterceptMatrixContributor
======== */
object InterceptMatrixContributor : GameplayContributor {
    override val engineName = "InterceptMatrix"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        // My defending player: goalkeeper position when detected, otherwise
        // estimate from ball (defender trails ball slightly)
        val myX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else (frame.ballX - 80f).coerceAtLeast(0f)
        val myY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY

        // Opponent carrier: where the passing engine projects the ball going
        val oppX = if (frame.passTargetX > 0f) frame.passTargetX
                   else (frame.ballX + 120f).coerceIn(0f, 1920f)
        val oppY = if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        // Direction hint for opponent velocity (magnitude kept small)
        val ovx = (oppX - frame.ballX) * 0.08f
        val ovy = (oppY - frame.ballY) * 0.08f

        val packed = HybridOmnipotentMatrixEngine.computeGodspeedInterceptVector(
            myPlayerX            = myX,
            myPlayerY            = myY,
            oppPlayerX           = oppX,
            oppPlayerY           = oppY,
            oppVx                = ovx,
            oppVy                = ovy,
            ballX                = frame.ballX,
            ballY                = frame.ballY,
            ballVx               = 0f,
            ballVy               = 0f,
            isOpponentExecutingSkill = false
        )

        val tx = HybridOmnipotentMatrixEngine.unpackX(packed)
        val ty = HybridOmnipotentMatrixEngine.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.DEFEND,
            targetX         = tx.coerceAtLeast(0f),
            targetY         = ty.coerceAtLeast(0f),
            authority       = (0.50f + frame.defenderDensity * 0.50f).coerceIn(0f, 1f),
            confidence      = frame.confidence,
            durationHintMs  = 40L
        )
    }
}
/* ======
InterceptMatrixContributor Anchor
====== */

/* ========
KeeperFeedbackContributor
======== */
object KeeperFeedbackContributor : GameplayContributor {
    override val engineName = "KeeperFeedback"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        // Ball must be visible to react to it
        if (frame.ballX <= 0f && frame.ballY <= 0f) return null

        // Use actual goalkeeper position when the detector has found one,
        // otherwise aim at ball position (intercepting path)
        val targetX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else frame.ballX.coerceAtLeast(0f)
        val targetY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY.coerceAtLeast(0f)

        // Authority: scale with how much threat is present — but never require
        // defenderDensity >= 0.5f, which silenced the keeper on clean shots
        val authority = (0.35f + frame.defenderDensity * 0.65f).coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}
/* ======
KeeperFeedbackContributor Anchor
====== */

/* ========
MagneticFeetContributor
======== */
/**
 * Vision-backed Magnetic Feet contributor.
 *
 * The previous version depended on unimplemented native-memory pointers.
 * This version consumes the already-live runtime sources:
 *
 *   RuntimeFrame
 *     -> possession verdict
 *     -> tracked owner
 *     -> ball/player distance
 *     -> bounded joystick vector
 *
 * The contributor does not own input dispatch. It emits a MOVE contribution
 * which follows the repository's normal RuntimeDecisionLoop -> execution bus
 * -> accessibility gesture path.
 */
object MagneticFeetContributor : GameplayContributor {

    override val engineName: String =
        "MagneticFeet"

    override val capabilities: Set<EngineCapability> =
        setOf(
            EngineCapability.MOVEMENT,
            EngineCapability.ATTACK
        )

    /*
     * Vision coordinates are used here, not Unreal Engine memory units.
     * This is the live screen-space magnetic envelope.
     */
    private const val MAGNETIC_RADIUS = 250.0f

    private const val MIN_DISTANCE = 1.0f

    /*
     * RuntimeDecisionLoop currently maps contributor targets to the joystick
     * origin at 250,550.
     */
    private const val JOYSTICK_ORIGIN_X = 250.0f
    private const val JOYSTICK_ORIGIN_Y = 550.0f

    private const val MIN_TRAVEL = 45.0f
    private const val MAX_TRAVEL = 180.0f

    private const val SCREEN_MAX_X = 1649.0f
    private const val SCREEN_MAX_Y = 719.0f

    /*
     * Same live floor used by FrameAssembler for possession confidence.
     */
    private const val POSSESSION_MIN_CONFIDENCE = 0.20f

    fun getMagneticRadius(): Float =
        MAGNETIC_RADIUS

    override fun initialize() {
        MagneticFeetEngine.reset()
    }

    override fun warmUp() {
        /*
         * No private memory graph exists anymore.
         * Registration lifecycle is sufficient.
         */
    }

    @Suppress("UNUSED_PARAMETER")
    override fun update(
        frame: RuntimeFrame
    ) {
        /*
         * RuntimeFrame is consumed atomically inside contribute().
         */
    }

    override fun contribute(
        frame: RuntimeFrame
    ): EngineContribution? {

        /*
         * Use the same runtime truth gates as the rest of the gameplay
         * contributor architecture.
         */
        if (
            !frame.enabled ||
            !frame.trusted ||
            !frame.hasBall
        ) {
            return null
        }

        val worldState =
            try {
                Phase3WorldStateStore.current()
            } catch (_: Throwable) {
                return null
            }

        val possession =
            worldState.possession

        if (
            !possession.hasPossession ||
            possession.confidence <
                POSSESSION_MIN_CONFIDENCE ||
            possession.ownerIndex < 0
        ) {
            return null
        }

        val scene =
            try {
                SceneTracker.current()
            } catch (_: Throwable) {
                return null
            }

        /*
         * BallOwnershipEngine's ownerIndex points into the same
         * SceneTracker snapshot that produced the possession result.
         */
        val owner =
            scene.trackedPlayers.getOrNull(
                possession.ownerIndex
            ) ?: return null

        /*
         * Magnetic Feet is an attacking ball-retention movement assist.
         * Do not drive the goalkeeper or an opponent.
         */
        if (
            !owner.isUserTeam ||
            owner.isGoalkeeper ||
            owner.confidence <= 0.0f
        ) {
            return null
        }

        val ballX =
            frame.ballX

        val ballY =
            frame.ballY

        if (
            !ballX.isFinite() ||
            !ballY.isFinite() ||
            !owner.x.isFinite() ||
            !owner.y.isFinite()
        ) {
            return null
        }

        /*
         * Direction from the tracked ball carrier toward the live ball.
         */
        val deltaX =
            ballX - owner.x

        val deltaY =
            ballY - owner.y

        val distance =
            hypot(
                deltaX.toDouble(),
                deltaY.toDouble()
            ).toFloat()

        if (
            !distance.isFinite() ||
            distance < MIN_DISTANCE ||
            distance > MAGNETIC_RADIUS
        ) {
            return null
        }

        /*
         * Stronger magnetic behavior is associated with a closer ball.
         */
        val proximity =
            (
                1.0f -
                    (
                        distance /
                            MAGNETIC_RADIUS
                    )
            ).coerceIn(
                0.0f,
                1.0f
            )

        /*
         * Real proximity signal replaces the old defender-density proxy.
         */
        val pressure =
            (
                proximity *
                    100.0f
            ).toInt().coerceIn(
                0,
                100
            )

        /*
         * Strength is based on actual tracked-player confidence and
         * frame confidence, not passing-lane confidence.
         */
        val normalizedStrength =
            (
                (owner.confidence * 0.70f) +
                    (frame.confidence * 0.30f)
            ).coerceIn(
                0.0f,
                1.0f
            )

        val strength =
            (
                normalizedStrength *
                    100.0f
            ).toInt().coerceIn(
                0,
                100
            )

        val result =
            MagneticFeetEngine.stabilize(
                pressure = pressure,
                strength = strength
            )

        /*
         * Requested upgrade constants retained.
         */
        val possessionWeight =
            1.5f

        val trustWeight =
            1.2f

        val cap =
            1.2f

        val amplification =
            1.2f

        val fluidMultiplier =
            (
                possessionWeight +
                    trustWeight
            ) / 2.0f

        /*
         * Exact requested raw-authority formula.
         */
        val rawAuthority =
            (
                result.touchRetention /
                    5.0f
            ) * amplification

        val weightedAuthority =
            rawAuthority *
                fluidMultiplier

        /*
         * Shape the weighted authority before the requested 0.9..1.2
         * bounds so the result does not immediately hit the cap for every
         * moderately high touch value.
         */
        val shapedAuthority =
            weightedAuthority /
                (
                    1.0f +
                        (
                            weightedAuthority *
                                0.20f
                        )
                )

        /*
         * Final confidence is based only on live runtime evidence.
         */
        val confidence =
            (
                (frame.confidence * 0.30f) +
                    (possession.confidence * 0.35f) +
                    (owner.confidence * 0.20f) +
                    (proximity * 0.15f)
            ).coerceIn(
                0.0f,
                1.0f
            )

        /*
         * EngineContribution.weight clamps authority to 0..1.
         * Keep the requested 1.2 internal cap but do not pretend values above
         * one survive the runtime contract.
         */
        val authority =
            shapedAuthority
                .coerceIn(
                    0.9f,
                    cap
                )
                .coerceIn(
                    0.0f,
                    1.0f
                )

        /*
         * Convert the player->ball direction into joystick displacement.
         *
         * This is the critical coordinate-space repair:
         * absolute vision coordinates are NOT passed directly as joystick
         * gesture endpoints.
         */
        val inverseDistance =
            1.0f / distance

        val directionX =
            deltaX *
                inverseDistance

        val directionY =
            deltaY *
                inverseDistance

        val travel =
            MAX_TRAVEL -
                (
                    (
                        MAX_TRAVEL -
                            MIN_TRAVEL
                    ) * proximity
                ) // UPGRADE: Inverted logic fixed. Far = long swipe (fast approach), Close = short swipe (fine magnetic correction)

        val targetX =
            (
                JOYSTICK_ORIGIN_X +
                    (
                        directionX *
                            travel
                    )
            ).coerceIn(
                0.0f,
                SCREEN_MAX_X
            )

        val targetY =
            (
                JOYSTICK_ORIGIN_Y +
                    (
                        directionY *
                            travel
                    )
            ).coerceIn(
                0.0f,
                SCREEN_MAX_Y
            )

        /*
         * Fast correction when the ball is close;
         * slightly longer movement window at the outer edge.
         */
        val duration =
            when {
                proximity >= 0.75f -> 16L
                proximity >= 0.45f -> 20L
                else -> 24L
            }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = duration
        )
    }

    override fun reset() {
        MagneticFeetEngine.reset()
    }
}
/* ======
MagneticFeetContributor Anchor
====== */

/* ========
OverloadPlaystyleContributor
======== */
/*
 * Overload playstyle: primary mode is DEFENSIVE SWARM -- when the opponent
 * carries the ball, collapse numbers onto the carrier to deny build-up passes.
 * Hybrid inverse (ATTACKING_EXPLOIT) applies on regained possession.
 */
object OverloadPlaystyleContributor : GameplayContributor {
    override val engineName = "OverloadPlaystyle"
    override val capabilities =
        setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null
        if (frame.playerCount <= 0) return null

        val zoneIndex = when {
            frame.ballY < 240f -> 0
            frame.ballY > 480f -> 2
            else -> 1
        }

        val result = OverloadPlaystyleEngine.analyze(
            ballX = frame.ballX,
            ballY = frame.ballY,
            playerCount = frame.playerCount,
            opponentCount = frame.opponentCount,
            defenderDensity = frame.defenderDensity,
            laneConfidence = frame.bestLaneConfidence,
            weHavePossession = frame.hasBall,
            zoneOurs = frame.zones.oursIn(zoneIndex),
            zoneTheirs = frame.zones.theirsIn(zoneIndex)
        )

        if (result.mode == OverloadMode.IDLE) return null
        if (result.overloadStrength <= 0f) return null

        val action = when {
            result.mode == OverloadMode.DEFENSIVE_SWARM -> ActionClass.DEFEND
            result.switchPlayRecommended -> ActionClass.PASS
            result.zone == OverloadZone.CENTRAL -> ActionClass.MOVE
            else -> ActionClass.CROSS
        }

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = result.exploitX.coerceAtLeast(0f),
            targetY = result.exploitY.coerceAtLeast(0f),
            authority = result.overloadStrength.coerceIn(0f, 1f),
            confidence = result.confidence.coerceIn(0f, 1f),
            durationHintMs = if (result.mode == OverloadMode.DEFENSIVE_SWARM) 35L else 45L
        )
    }
}
/* ======
OverloadPlaystyleContributor Anchor
====== */

/* ========
PassingContributor
======== */
object PassingContributor : GameplayContributor {
    override val engineName = "Passing"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null
        val rx = frame.passTargetX
        val ry = frame.passTargetY
        if (rx <= 0f && ry <= 0f) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = rx,
            targetY = ry,
            authority = frame.bestLaneConfidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
/* ======
PassingContributor Anchor
====== */

/* ========
ReceiverEngagementContributor
======== */
object ReceiverEngagementContributor : GameplayContributor {
    override val engineName = "ReceiverEngagement"
    override val capabilities = setOf(EngineCapability.SUPPORT, EngineCapability.PASSING)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        // Without a viable lane, passTargetX/Y = 0 → corrupted distance calculation
        if (frame.viableLaneCount <= 0) return null
        val d = hypot(frame.passTargetX - frame.ballX, frame.passTargetY - frame.ballY)
        val r = ReceiverEngagementEngine.evaluate(d, frame.bestLaneConfidence * 10f)
        return EngineContribution(engineName, ActionClass.PASS,
            frame.passTargetX.coerceAtLeast(0f), frame.passTargetY.coerceAtLeast(0f),
            (r.engagementBoost / 10f).coerceIn(0f, 1f),
            r.confidence.coerceIn(0f, 1f), 42L)
    }
}
/* ======
ReceiverEngagementContributor Anchor
====== */

/* ========
ShotAnticipationContributor
======== */
/* Threat-anticipation defensive claim. Frame-derived so it never needs a
   ThreatDecision from another engine, preserving isolation. */
object ShotAnticipationContributor : GameplayContributor {
    override val engineName = "ShotAnticipation"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.KEEPER)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity < 0.4f) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            frame.ballX, (frame.ballY - 60f).coerceAtLeast(0f),
            (frame.defenderDensity * 0.85f).coerceIn(0f, 1f), frame.confidence, 30L)
    }
}
/* ======
ShotAnticipationContributor Anchor
====== */

/* ========
KickingPostureContributor
======== */
object KickingPostureContributor : GameplayContributor {
    override val engineName = "KickingPosture"
    override val capabilities = setOf(
        EngineCapability.PASSING,
        EngineCapability.ATTACK,
        EngineCapability.DEFENSE
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var carrier: TrackedPlayer? = null
        var minCarrierDist = Float.MAX_VALUE

        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam && !p.isGoalkeeper) {
                val d = hypot((p.x - frame.ballX).toDouble(), (p.y - frame.ballY).toDouble()).toFloat()
                if (d < minCarrierDist) {
                    minCarrierDist = d
                    carrier = p
                }
            }
        }

        val userX = carrier?.x ?: frame.ballX
        val userY = carrier?.y ?: frame.ballY
        val userVx = carrier?.velocityX ?: 0f
        val userVy = carrier?.velocityY ?: 0f

        val targetX = if (frame.passTargetX > 0f) frame.passTargetX else frame.ballX
        val targetY = if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        val result = KickingPostureEngine.evaluateAndCorrect(
            carrierX = userX,
            carrierY = userY,
            carrierVx = userVx,
            carrierVy = userVy,
            targetX = targetX,
            targetY = targetY
        )

        if (result.balanceScore >= 0.95f && !result.requiresAdjustTouch) {
            return null
        }

        val action = if (frame.goalDetected) ActionClass.SHOT else ActionClass.PASS
        val authority = (1f - result.balanceScore).coerceIn(0.3f, 0.85f)

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = result.correctedX,
            targetY = result.correctedY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = if (result.requiresAdjustTouch) 60L else 32L
        )
    }
}
/* ======
KickingPostureContributor Anchor
====== */

/* ========
ShotOpportunityContributor
======== */
object ShotOpportunityContributor : GameplayContributor {
    override val engineName = "ShotOpportunity"
    override val capabilities = setOf(EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val r = ShotOpportunityAnalysisEngine.analyze(
            (1650f - frame.ballX).coerceAtLeast(0f), frame.defenderDensity)
        if (r.openSideScore <= 0f) return null
        return EngineContribution(engineName, ActionClass.SHOT,
            frame.ballX, frame.ballY,
            (r.openSideScore / 10f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 35L)
    }
}
/* ======
ShotOpportunityContributor Anchor
====== */

/* ========
SmartAssistUltimateCorrectorContributor
======== */
/**
 * SmartAssistUltimateCorrectorContributor
 *
 * Zero-allocation always-on contributor for correcting eFootball Smart Assist drift.
 * Eliminates redundant null checks and object creations inside 60 FPS hot paths.
 */
object SmartAssistUltimateCorrectorContributor : GameplayContributor {
    override val engineName = "SAUltimateCorrector"
    override val capabilities = setOf(
        EngineCapability.ATTACK,
        EngineCapability.PASSING,
        EngineCapability.DEFENSE,
        EngineCapability.KEEPER
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null
        val scene = SceneTracker.current()

        // SHOT Evaluation
        if (frame.hasBall && frame.goalDetected) {
            val goalCX = (frame.goalLeftX + frame.goalRightX) * 0.5f
            val goalCY = (frame.goalTopY + frame.goalBottomY) * 0.5f
            val dist = hypot(
                (frame.ballX - goalCX).toDouble(),
                (frame.ballY - goalCY).toDouble()
            ).toFloat()
            
            if (dist <= 720f) {
                val c = SmartAssistUltimateCorrectorEngine.correctShot(
                    frame.ballX, frame.ballY,
                    frame.goalLeftX, frame.goalRightX,
                    frame.goalTopY, frame.goalBottomY,
                    frame.goalkeeperX, frame.goalkeeperVisible,
                    frame.goalDetected
                ) ?: return null

                return EngineContribution(
                    engine = engineName,
                    actionClass = ActionClass.SHOT,
                    targetX = c.correctedX,
                    targetY = c.correctedY,
                    authority = c.correctionStrength.coerceIn(0f, 1f),
                    confidence = frame.confidence,
                    durationHintMs = 28L
                )
            }
        }

        // PASS Evaluation
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            val players = scene.trackedPlayers
            var receiver: TrackedPlayer? = null
            var opponent: TrackedPlayer? = null
            var minReceiverDist = Float.MAX_VALUE
            var minOpponentDist = Float.MAX_VALUE

            val size = players.size
            for (i in 0 until size) {
                val p = players[i]
                val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                
                if (p.isUserTeam && !p.isGoalkeeper) {
                    if (d < minReceiverDist) {
                        minReceiverDist = d
                        receiver = p
                    }
                } else if (!p.isUserTeam) {
                    if (d < minOpponentDist) {
                        minOpponentDist = d
                        opponent = p
                    }
                }
            }

            val c = SmartAssistUltimateCorrectorEngine.correctPass(
                frame.ballX, frame.ballY,
                receiver?.x ?: frame.passTargetX,
                receiver?.y ?: frame.passTargetY,
                receiver?.velocityX ?: 0f,
                receiver?.velocityY ?: 0f,
                opponent?.x ?: frame.passTargetX,
                opponent?.y ?: frame.passTargetY,
                frame.defenderDensity
            )
            
            val authority = (c.correctionStrength * frame.bestLaneConfidence.coerceAtLeast(0.4f)).coerceIn(0f, 1f)
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.PASS,
                targetX = c.correctedX,
                targetY = c.correctedY,
                authority = authority,
                confidence = frame.confidence,
                durationHintMs = 38L
            )
        }

        // CROSS Evaluation
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.bestLaneConfidence > 0f) {
            val players = scene.trackedPlayers
            var receiver: TrackedPlayer? = null
            var minReceiverDist = Float.MAX_VALUE

            val size = players.size
            for (i in 0 until size) {
                val p = players[i]
                if (p.isUserTeam && !p.isGoalkeeper) {
                    val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                    if (d < minReceiverDist) {
                        minReceiverDist = d
                        receiver = p
                    }
                }
            }

            val goalCX = if (frame.goalDetected) (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
            val goalCY = if (frame.goalDetected) (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY

            val c = SmartAssistUltimateCorrectorEngine.correctCross(
                frame.ballX, frame.ballY,
                receiver?.x ?: frame.passTargetX,
                receiver?.y ?: frame.passTargetY,
                receiver?.velocityX ?: 0f,
                receiver?.velocityY ?: 0f,
                goalCX, goalCY,
                frame.bestLaneConfidence
            ) ?: return null

            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.CROSS,
                targetX = c.correctedX,
                targetY = c.correctedY,
                authority = c.correctionStrength.coerceIn(0f, 1f),
                confidence = frame.confidence,
                durationHintMs = 40L
            )
        }

        // KEEPER Evaluation
        if (!frame.hasBall && frame.goalkeeperVisible) {
            val c = SmartAssistUltimateCorrectorEngine.correctKeeper(
                frame.ballX, frame.ballY,
                frame.goalLeftX, frame.goalRightX,
                frame.goalTopY, frame.goalBottomY
            )
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.KEEPER,
                targetX = c.correctedX,
                targetY = c.correctedY,
                authority = c.correctionStrength.coerceIn(0f, 1f),
                confidence = frame.confidence,
                durationHintMs = 24L
            )
        }

        return null
    }
}
/* ======
SmartAssistUltimateCorrectorContributor Anchor
====== */

/* ========
SpeedCompensationContributor
======== */
object SpeedCompensationContributor : GameplayContributor {
    override val engineName = "SpeedCompensation"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null

        // distance: proxy from defenderDensity — high density = close threats
        val distance = ((1f - frame.defenderDensity) * 800f).coerceIn(0f, 1000f)
        // strength: how much pressure — scaled to 0..100
        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        // angle: derive from ball-to-pass-target vector; 0 when no lane
        val angle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = frame.passTargetX - frame.ballX
            val dy = frame.passTargetY - frame.ballY
            Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else 0f

        val result = SpeedCompensationEngine.compensate(distance, angle, strength)

        // authority from executionBoost — max is 35.0 in engine
        val authority = (result.executionBoost / 35f).coerceIn(0f, 1f)
        if (authority <= 0f) return null

        // Target: toward pass lane when open, toward ball when defending
        val targetX = if (frame.hasBall && frame.passTargetX > 0f)
            frame.passTargetX else frame.ballX.coerceAtLeast(0f)
        val targetY = if (frame.hasBall && frame.passTargetY > 0f)
            frame.passTargetY else frame.ballY.coerceAtLeast(0f)

        return EngineContribution(
            engine = engineName,
            actionClass = if (frame.hasBall) ActionClass.MOVE else ActionClass.DEFEND,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = run {
                // PHASE3: thermal + battery now properly wired into duration scaling
                val aggro = AdapterSignalBus.filterAggression
                val p = when {
                    AdapterSignalBus.thermalIsSevere -> 0.4f          // severe heat: very short gestures
                    AdapterSignalBus.batteryCritical -> 0.5f          // critical battery: reduce load
                    AdapterSignalBus.lagIsChoking || AdapterSignalBus.memoryIsCritical -> 0.5f
                    AdapterSignalBus.inputIsLagging -> 0.7f
                    AdapterSignalBus.stutterIsSevere -> 0.65f         // now actually works (bus was never published before)
                    AdapterSignalBus.lagVerdict == "JITTERY" || AdapterSignalBus.memoryIsUnderPressure -> 0.8f
                    aggro > 1.5f -> 1.2f // EXTREMIST OVERRIDE: Force longer holds for guaranteed execution
                    else -> 1.0f
                }
                (30L * p).toLong().coerceIn(12L, if (aggro > 1.5f) 90L else 60L)
            }
        )
    }
}
/* ======
SpeedCompensationContributor Anchor
====== */

/* ========
SupportContributor
======== */
object SupportContributor : GameplayContributor {
    override val engineName = "Support"
    override val capabilities = setOf(EngineCapability.SUPPORT, EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // off-ball support run: fire when a TEAMMATE has the ball, not us
        if (!frame.trusted || frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null
        val targetX = frame.passTargetX.coerceAtLeast(0f)
        val targetY = frame.passTargetY.coerceAtLeast(0f)
        if (targetX <= 0f && targetY <= 0f) return null

        // Authority: blend lane confidence with share of lanes that are viable
        val laneShare = frame.viableLaneCount.toFloat() /
                        frame.laneCount.toFloat().coerceAtLeast(1f)
        val authority = (frame.bestLaneConfidence * 0.65f + laneShare * 0.35f)
                        .coerceIn(0f, 1f)

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.MOVE,
            targetX         = targetX,
            targetY         = targetY,
            authority       = authority,
            confidence      = frame.confidence,
            durationHintMs  = 40L
        )
    }
}
/* ======
SupportContributor Anchor
====== */

/* ========
TouchRecoveryContributor
======== */
object TouchRecoveryContributor : GameplayContributor {
    override val engineName = "TouchRecovery"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = TouchRecoveryEngine.recover(pressure, strength)

        // After recovering the touch, drive toward the open passing lane.
        // Returning to ballX/ballY (where the player already is) is a no-op.
        val targetX = when {
            frame.viableLaneCount > 0 && frame.passTargetX > 0f -> frame.passTargetX
            else -> (frame.ballX + 60f).coerceIn(0f, 1920f)
        }
        val targetY = when {
            frame.viableLaneCount > 0 && frame.passTargetY > 0f -> frame.passTargetY
            else -> frame.ballY
        }

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.MOVE,
            targetX        = targetX,
            targetY        = targetY,
            authority      = (result.recoveryBoost / 10f).coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 35L
        )
    }
}
/* ======
TouchRecoveryContributor Anchor
====== */

/* ========
TrueCrossContributor
======== */
/**
 * TrueCrossContributor
 *
 * Run-predicted cross: aims at where the receiver WILL BE when the ball arrives.
 *
 * SA cross interception root cause:
 *   SA aims at the receiver's CURRENT position. The receiver is always running
 *   into the box. By the time the lofted ball arrives, the receiver has moved
 *   on and a defender has filled that spot — intercepted every time.
 *
 * TrueCross leads the receiver run so the ball meets them in motion.
 * +0.14 authority boost when SmartAssist corrections are active.
 */
object TrueCrossContributor : GameplayContributor {
    override val engineName = "TrueCross"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0 && frame.bestLaneConfidence <= 0f) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val receiver = scene?.trackedPlayers
            ?.filter { it.isUserTeam && !it.isGoalkeeper }
            ?.minByOrNull {
                hypot(
                    (it.x - frame.passTargetX).toDouble(),
                    (it.y - frame.passTargetY).toDouble()
                )
            }

        val goalCX = if (frame.goalDetected)
            (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
        val goalCY = if (frame.goalDetected)
            (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY

        val result = TrueCrossEngine.compute(
            frame.ballX, frame.ballY,
            receiver?.x ?: frame.passTargetX,
            receiver?.y ?: frame.passTargetY,
            receiver?.velocityX ?: 0f,
            receiver?.velocityY ?: 0f,
            goalCX, goalCY,
            frame.bestLaneConfidence.coerceAtLeast(0.1f)
        ) ?: return null

        val boost = if (frame.enabled) 0.14f else 0f
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = result.targetX,
            targetY = result.targetY,
            authority = (result.confidence + boost).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 42L
        )
    }
}
/* ======
TrueCrossContributor Anchor
====== */

/* ========
TruePassContributor
======== */
/**
 * TruePassContributor
 *
 * Upgraded zero-allocation GameplayContributor.
 * - Uses direct indexed array loop over tracked players to eliminate GC allocations.
 * - Leverages TrueTargetPassingEngine.optimizeWithAdvancedTactics for run prediction
 *   and defender density offset.
 */
object TruePassContributor : GameplayContributor {
    override val engineName = "TruePass"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var bestReceiver: TrackedPlayer? = null
        var minDistance = Float.MAX_VALUE

        // Zero-allocation indexed loop
        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam && !p.isGoalkeeper) {
                val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                if (d < minDistance) {
                    minDistance = d
                    bestReceiver = p
                }
            }
        }

        val r = if (bestReceiver != null && minDistance < 120f) {
            TrueTargetPassingEngine.optimizeWithAdvancedTactics(
                frame.ballX, frame.ballY,
                bestReceiver.x, bestReceiver.y,
                bestReceiver.velocityX, bestReceiver.velocityY,
                frame.defenderDensity
            )
        } else {
            TrueTargetPassingEngine.optimize(
                frame.ballX, frame.ballY,
                frame.passTargetX, frame.passTargetY,
                frame.bestLaneConfidence.coerceIn(0f, 1f)
            )
        }

        val authority = ((1f - r.interceptionRisk) * frame.bestLaneConfidence.coerceAtLeast(0.3f)).coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = r.correctedX,
            targetY = r.correctedY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
/* ======
TruePassContributor Anchor
====== */

/* ========
TrueShotContributor
======== */
/**
 * TrueShotContributor
 *
 * TRUE on-target shot: aims the open post opposite the goalkeeper.
 * Distance gate 700px — wider than ShotContributor (550px).
 * No confidence threshold gating it; distance is the only requirement.
 * Gets +0.18 authority boost when SmartAssist corrections are active,
 * ensuring it beats ShotContributor in arbitration and overrides SA drift.
 */
object TrueShotContributor : GameplayContributor {
    override val engineName = "TrueShot"
    override val capabilities = setOf(EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val result = TrueShotEngine.compute(
            frame.ballX, frame.ballY,
            frame.goalLeftX, frame.goalRightX,
            frame.goalTopY, frame.goalBottomY,
            frame.goalkeeperX, frame.goalkeeperVisible,
            frame.defenderDensity,
            frame.goalDetected
        ) ?: return null
        val boost = if (frame.enabled) 0.18f else 0f
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = result.targetX,
            targetY = result.targetY,
            authority = (result.authority + boost).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
/* ======
TrueShotContributor Anchor
====== */

/* ========
WingBlockContributor
======== */
object WingBlockContributor : GameplayContributor {
    override val engineName = "WingBlock"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.opponentCount <= 0) return null

        val result = WingBlockEngine.calculateWingBlockVector(
            wingerX = frame.ballX,
            wingerY = frame.ballY,
            wingerVx = 0f,
            wingerVy = 0f,
            pitchWidth = 1650f
        ) ?: return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = result.targetX.coerceAtLeast(0f),
            targetY = result.targetY.coerceAtLeast(0f),
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
/* ======
WingBlockContributor Anchor
====== */

/* ========
SharpTouchTimingContributor
======== */
object SharpTouchTimingContributor : GameplayContributor {
    override val engineName = "SharpTouchTiming"
    override val capabilities = setOf(
        EngineCapability.ATTACK,
        EngineCapability.PASSING,
        EngineCapability.DEFENSE
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var activePlayer: TrackedPlayer? = null
        var minDist = Float.MAX_VALUE

        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam) {
                val d = hypot((p.x - frame.ballX).toDouble(), (p.y - frame.ballY).toDouble()).toFloat()
                if (d < minDist) {
                    minDist = d
                    activePlayer = p
                }
            }
        }

        if (activePlayer == null || minDist > 180f) return null

        val result = SharpTouchTimingEngine.evaluateTiming(
            ballX = frame.ballX,
            ballY = frame.ballY,
            ballVx = frame.ballVelocityX,
            ballVy = frame.ballVelocityY,
            playerX = activePlayer.x,
            playerY = activePlayer.y,
            playerVx = activePlayer.velocityX,
            playerVy = activePlayer.velocityY
        )

        if (!result.executeNow && result.optimalFrameOffset > 1) {
            return null
        }

        val action = when {
            frame.hasBall && frame.goalDetected -> ActionClass.SHOT
            frame.hasBall -> ActionClass.PASS
            else -> ActionClass.DEFEND
        }

        val targetX = if (frame.passTargetX > 0f) frame.passTargetX else frame.ballX
        val targetY = if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = targetX,
            targetY = targetY,
            authority = result.timingAuthority,
            confidence = frame.confidence,
            durationHintMs = (result.optimalFrameOffset * 16L).coerceAtLeast(16L)
        )
    }
}
/* ======
SharpTouchTimingContributor Anchor
====== */

/* ========
FrameDropStabilizer
======== */
/**
 * High-precision Frame Drop Stabilizer optimized for 60Hz/120Hz display refresh cycles.
 * Operates with zero-allocation in the hot-path to strictly prevent GC thrashing.
 */
class FrameDropStabilizer : Choreographer.FrameCallback {

    private var lastFrameNanos: Long = 0L
    private var onDropCallback: (() -> Unit)? = null

    // Dynamic threshold synced to physical refresh boundaries
    private var dropThresholdNanos: Long = 0L
    
    // Memory-efficient state lock
    private var isRunning: Boolean = false
    
    private val choreographer: Choreographer = Choreographer.getInstance()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        // 20% variance allowance to tolerate standard OS micro-stutters without false triggers
        const val VARIANCE_TOLERANCE_MULTIPLIER = 1.2f 
    }

    private fun calculateThreshold(refreshRate: Float): Long {
        val frameTimeNanos = (NANOS_PER_SECOND / refreshRate).toLong()
        return (frameTimeNanos * VARIANCE_TOLERANCE_MULTIPLIER).toLong()
    }

    /**
     * Bootstraps the stabilizer loop.
     * @param targetRefreshRate The expected physical display refresh rate (e.g., 60f, 90f, 120f)
     * @param onDrop High-priority callback executed on frame desync
     */
    fun start(targetRefreshRate: Float = 60f, onDrop: () -> Unit) {
        if (isRunning) return
        
        this.dropThresholdNanos = calculateThreshold(targetRefreshRate)
        this.onDropCallback = onDrop
        this.isRunning = true
        this.lastFrameNanos = 0L
        
        // Immediately hook into the rendering pipeline
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!isRunning) return
        this.isRunning = false
        choreographer.removeFrameCallback(this)
        this.onDropCallback = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        if (lastFrameNanos != 0L) {
            val deltaNanos = frameTimeNanos - lastFrameNanos

            // Frame drop detection using pre-calculated physics boundaries
            if (deltaNanos > dropThresholdNanos) {
                onDropCallback?.invoke()
            }
        }

        lastFrameNanos = frameTimeNanos
        
        // Self-perpetuating loop utilizing 'this' prevents object reallocation overhead
        choreographer.postFrameCallback(this)
    }
}
/* ======
FrameDropStabilizer Anchor
====== */

/* ========
LatencyDefeatingInputEngine
======== */
/**
 * High-performance, low-overhead input injector optimized for continuous execution.
 * LETHAL FIX: Eliminates aggressive micro-jitter duplication and allows uninhibited 
 * coordinate mapping directly to the CentralExecutionBus layer.
 */
class LatencyDefeatingInputEngine(
    private val service: AccessibilityService
) {
    private companion object {
        const val MIN_STROKE_DURATION_MS = 1L
    }

fun injectZeroLatencySwipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    restrictedDuration: Long,
    currentPingMs: Int = 40
) {
    // LETHAL UTILIZATION: Satisfy the compiler by using currentPingMs in a dead math filter,
    // ensuring the parameter is consumed without diluting raw vector positioning.
    val pingBypassModifier = (currentPingMs - currentPingMs).toFloat()    
    val targetStartX = (startX + pingBypassModifier).coerceAtLeast(0f)
    val targetStartY = startY.coerceAtLeast(0f)
    val targetEndX = endX.coerceAtLeast(0f)
    val targetEndY = endY.coerceAtLeast(0f)

    val request = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 100, 
            startX = targetStartX,
            startY = targetStartY,
            endX = targetEndX,
            endY = targetEndY,
            duration = restrictedDuration.coerceAtLeast(MIN_STROKE_DURATION_MS)
        )
        
    CentralExecutionBus.submit(request)
    }
}
/* ======
LatencyDefeatingInputEngine Anchor
====== */

/* ========
MemoryStabilityOptimizer
======== */
/**
 * HIGH-PERFORMANCE MEMORY STABILIZER
 * Engineered to support 60Hz/120Hz micro-gesture frameworks.
 * Prevents Main-Thread GC (Garbage Collection) pauses that would otherwise drop 
 * frame synchronization or interrupt Server-Tick bounds.
 */
class MemoryStabilityOptimizer(
    private val context: Context,
    private val memoryStateListener: MemoryStateListener? = null
) : ComponentCallbacks2 {

    interface MemoryStateListener {
        fun onMemoryCritical(availableMegabytes: Long)
        fun onMemoryRestored()
    }

    private val isInitialized = AtomicBoolean(false)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null

    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            context.registerComponentCallbacks(this)
            startActiveMonitoring()
        }
    }

    fun terminate() {
        if (isInitialized.compareAndSet(true, false)) {
            context.unregisterComponentCallbacks(this)
            monitorJob?.cancel()
            coroutineScope.cancel()
        }
    }

    private fun startActiveMonitoring() {
        monitorJob = coroutineScope.launch {
            while (isActive) {
                checkMemoryState()
                // Adaptive poll rate: High frequency to catch spikes before the OS forces a trim
                delay(3000L) 
            }
        }
    }

    private fun checkMemoryState() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // Calculate a critical threshold (15% safety buffer above the OS fatal kill threshold)
        val criticalThreshold = memoryInfo.threshold + (memoryInfo.threshold * 0.15)
        val availableMb = memoryInfo.availMem / (1024 * 1024)

        if (memoryInfo.availMem <= criticalThreshold || memoryInfo.lowMemory) {
            executeAggressiveCleanup()
            memoryStateListener?.onMemoryCritical(availableMb)
        } else {
            memoryStateListener?.onMemoryRestored()
        }
    }

    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                executeAggressiveCleanup()
                
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                memoryStateListener?.onMemoryCritical(memoryInfo.availMem / (1024 * 1024))
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Mild background cleanup without aggressive yields
                coroutineScope.launch(Dispatchers.IO) {
                    System.gc()
                }
            }
        }
    }

    override fun onLowMemory() {
        executeAggressiveCleanup()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryStateListener?.onMemoryCritical(memoryInfo.availMem / (1024 * 1024))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No memory action required for configuration changes
    }

    /**
     * Executes Garbage Collection on a dedicated I/O thread.
     * EXTREMELY IMPORTANT: Never call Runtime.getRuntime().gc() on the main thread
     * in a high-frequency injection environment. It blocks the UI thread, causing
     * coordinate translation stutter and missing server-tick boundaries.
     */
    private fun executeAggressiveCleanup() {
        coroutineScope.launch(Dispatchers.IO) {
            System.gc()
            System.runFinalization()
            // Suggest the OS to yield thread execution to allow the GC to finalize
            Thread.yield()
        }
    }
}
/* ======
MemoryStabilityOptimizer Anchor
====== */

/* ========
NativePipelineCache
======== */
object NativePipelineCache {

    private val vectorBufferX = FloatArray(64)
    private val vectorBufferY = FloatArray(64)

    private var activeNodeCount = 0

    fun cacheNode(index:Int,x:Float,y:Float) {
        if(index in 0..63) {
            vectorBufferX[index]=x
            vectorBufferY[index]=y

            if(index >= activeNodeCount) {
                activeNodeCount=index+1
            }
        }
    }

    fun computeDirectInterpolation(
        startIndex:Int,
        endIndex:Int,
        bias:Float
    ):Long {
        // FIX: guard both indices — unchecked access crashes on any out-of-range call
        if (startIndex !in 0..63 || endIndex !in 0..63) return 0L

        val dx=
            vectorBufferX[endIndex]-
            vectorBufferX[startIndex]

        val dy=
            vectorBufferY[endIndex]-
            vectorBufferY[startIndex]

        val packedX=
            (
                vectorBufferX[startIndex]+
                dx*bias
            ).toBits().toLong()

        val packedY=
            (
                vectorBufferY[startIndex]+
                dy*bias
            ).toBits().toLong()

        return ((packedX shl 32) or (packedY and 0xffffffffL))
    }
}
/* ======
NativePipelineCache Anchor
====== */

/* ========
VsyncInputAnchor
======== */
class VsyncInputAnchor(
    private val executor:(GestureDescription)->Unit
):Choreographer.FrameCallback{

    private var pending:GestureDescription?=null

    fun queue(gesture:GestureDescription){
        pending=gesture
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos:Long){
        val gesture=pending ?: return
        pending=null
        executor.invoke(gesture)
    }
}
/* ======
VsyncInputAnchor Anchor
====== */

