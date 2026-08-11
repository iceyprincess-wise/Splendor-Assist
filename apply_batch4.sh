python3 << 'PYEOF'
import os, sys

ROOT = os.path.expanduser("~/projects/Splendor-Assist")
SA   = os.path.join(ROOT, "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")
CTR  = os.path.join(SA, "contributors")
ok = []; fail = []

def w(rel, content):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path,"w") as f: f.write(content)
    ok.append(rel); print("  OK ", rel)

SA_R  = "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
CTR_R = SA_R + "/contributors"

# ── FILE 1: TrueTargetPassingEngine.kt ──────────────────────
w(SA_R+"/TrueTargetPassingEngine.kt", '''\
package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class PassingAssistResult(
    val correctedX: Float,
    val correctedY: Float,
    val interceptionRisk: Float
)

object TrueTargetPassingEngine {

    fun optimize(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        retention: Float
    ): PassingAssistResult {
        val dx = endX - startX
        val dy = endY - startY
        // FIX: old formula endX+(dx*0.65f*retention) overshot past receiver.
        // Correct: arrive proportionally at startX + dx * retention.
        return PassingAssistResult(
            correctedX       = (startX + dx * retention).coerceIn(0f, 1650f),
            correctedY       = (startY + dy * retention).coerceIn(0f, 720f),
            interceptionRisk = (1f - retention).coerceIn(0f, 1f)
        )
    }

    fun interceptionVector(
        ballX: Float, ballY: Float,
        ballVelocityX: Float, ballVelocityY: Float,
        receiverX: Float, receiverY: Float
    ): Pair<Float,Float> {
        val lookAhead = 0.3f
        val px = ballX + ballVelocityX * lookAhead
        val py = ballY + ballVelocityY * lookAhead
        val dx = px - receiverX; val dy = py - receiverY
        val m  = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (m > 0f) Pair((dx/m)*100f, (dy/m)*100f) else Pair(0f,0f)
    }

    fun calculateDoublePressEscapeVector(
        carrierX: Float, carrierY: Float,
        presserAX: Float, presserAY: Float,
        presserBX: Float, presserBY: Float,
        strikerX: Float, strikerY: Float
    ): Pair<Float,Float>? {
        val d = hypot((presserAX-presserBX).toDouble(),(presserAY-presserBY).toDouble())
        if (d < 80.0) {
            val mx=(presserAX+presserBX)/2f; val my=(presserAY+presserBY)/2f
            return Pair(carrierX+(strikerX-mx)*0.3f, carrierY+(strikerY-my)*0.3f)
        }
        return Pair(carrierX+(strikerX-carrierX)*0.35f, carrierY+(strikerY-carrierY)*0.35f)
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
''')

# ── FILE 2: DefensiveCompactnessEngine.kt ───────────────────
w(SA_R+"/DefensiveCompactnessEngine.kt", '''\
package com.assistant.adapter.smartassist

import kotlin.math.sqrt

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
''')

# ── FILE 3: HybridOmnipotentMatrixEngine.kt ─────────────────
w(SA_R+"/HybridOmnipotentMatrixEngine.kt", '''\
package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.hypot
import kotlin.random.Random
import com.assistant.execution.ContributionRegistry

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
''')

# ── FILE 4: DefenseAuthorityEngine.kt ───────────────────────
w(SA_R+"/DefenseAuthorityEngine.kt", '''\
package com.assistant.adapter.smartassist

import android.util.Log
import kotlin.math.pow

data class DefenseAuthorityResult(
    val containment: Float,
    val interception: Float,
    val pressure: Float
)

// FIX: removed +/-8% random noise — made identical situations produce different authority.
object DefenseAuthorityEngine {

    data class DefenseEvaluationDiagnostics(
        val totalEvaluations: Long,
        val maxContainmentObserved: Float,
        val maxInterceptionObserved: Float,
        val lastDistanceEvaluated: Float,
        val lastUpdatedTimestamp: Long
    )

    private var evaluationCount: Long = 0L
    private var peakContainment: Float = 0f
    private var peakInterception: Float = 0f
    private var lastDistance: Float = 0f
    private var lastUpdateMs: Long = 0L

    @Synchronized fun getEvaluationDiagnostics() = DefenseEvaluationDiagnostics(
        evaluationCount, peakContainment, peakInterception, lastDistance, lastUpdateMs)

    fun evaluate(distance:Float, strength:Int, recovery:Float, retention:Float): DefenseAuthorityResult {
        val ns = strength.coerceIn(0,100)/100f
        val ti = ns.pow(1.5f)
        val pf = 1f - (distance.coerceIn(0f,1200f)/1200f)
        val nr = recovery.coerceIn(0f,10f)/10f
        val nt = retention.coerceIn(0f,10f)/10f

        val containment  = ((nr*5.5f)+(ti*3.5f)+(pf*2.5f)).coerceIn(0f,10f)
        val interception = ((nt*5.5f)+(ti*3.5f)+(pf*2.5f)).coerceIn(0f,10f)
        val pressure     = (containment+interception).coerceIn(0f,20f)

        synchronized(this) {
            evaluationCount++
            if (containment  > peakContainment)  peakContainment  = containment
            if (interception > peakInterception) peakInterception = interception
            lastDistance = distance; lastUpdateMs = System.currentTimeMillis()
        }
        if (evaluationCount % 500L == 0L)
            Log.d("DefenseAuthorityEngine","containment=$containment interception=$interception pressure=$pressure")

        return DefenseAuthorityResult(containment, interception, pressure)
    }
}
''')

# ── FILE 5: DefensiveLineEngine.kt ──────────────────────────
w(SA_R+"/DefensiveLineEngine.kt", '''\
package com.assistant.adapter.smartassist

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
''')

# ── FILE 6: InstantInterceptEngine.kt ───────────────────────
w(SA_R+"/InstantInterceptEngine.kt", '''\
package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

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
        val c = ownership.owner!!
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
''')

# ── FILE 7: BuildUpPressEngine.kt ───────────────────────────
w(SA_R+"/BuildUpPressEngine.kt", '''\
package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame

/** Presses carrier\'s CURRENT position — arrive NOW before they play the pass. Authority=1.0. */
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
        val c = ownership.owner!!
        if (c.isUserTeam) return ballFallback(frame)

        return PressResult(true, c.x.coerceIn(0f,SCREEN_W), c.y.coerceIn(0f,SCREEN_H), 1.0f)
    }

    private fun ballFallback(f: RuntimeFrame): PressResult {
        if (f.ballX<=0f && f.ballY<=0f) return PressResult(false)
        return PressResult(true, f.ballX, f.ballY, 1.0f)
    }
}
''')

# ── FILE 8: BallRetentionShieldEngine.kt ────────────────────
w(SA_R+"/BallRetentionShieldEngine.kt", '''\
package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame

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
''')

# ── FILE 9: InstantInterceptContributor.kt ──────────────────
w(CTR_R+"/InstantInterceptContributor.kt", '''\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.InstantInterceptEngine
import com.assistant.runtime.*

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
''')

# ── FILE 10: BuildUpPressContributor.kt ─────────────────────
w(CTR_R+"/BuildUpPressContributor.kt", '''\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.BuildUpPressEngine
import com.assistant.runtime.*

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
''')

# ── FILE 11: BallRetentionShieldContributor.kt ──────────────
w(CTR_R+"/BallRetentionShieldContributor.kt", '''\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.BallRetentionShieldEngine
import com.assistant.runtime.*

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
''')

# ── FILE 12: RuntimeCoordinator.kt — register 3 new contributors
rc = os.path.join(ROOT, SA_R+"/RuntimeCoordinator.kt")
needle = "            com.assistant.runtime.GameplayEngineRegistry.register(\n                com.assistant.adapter.smartassist.contributors.SpeedCompensationContributor)"
insert = """            // BATCH 4: instant intercept + build-up press + ball retention shield
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.InstantInterceptContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.BuildUpPressContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor)"""
with open(rc) as f: src = f.read()
if "InstantInterceptContributor" in src:
    print("  SKIP RuntimeCoordinator.kt (already registered)")
elif needle not in src:
    print("  ERR  RuntimeCoordinator.kt: anchor not found"); fail.append(rc)
else:
    with open(rc,"w") as f: f.write(src.replace(needle, needle+"\n"+insert, 1))
    ok.append(rc); print("  OK  RuntimeCoordinator.kt")

print(f"\n=== DONE: {len(ok)} OK, {len(fail)} FAIL ===")
if fail: sys.exit(1)
PYEOF
