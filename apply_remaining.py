#!/usr/bin/env python3
import os, sys

BASE    = "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
CONTRIB = BASE + "/contributors"

def ok(msg):   print("OK     " + msg)
def fail(msg): print("FAIL   " + msg); sys.exit(1)

def write(path, code):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(code)
    ok(path.split("/")[-1])

def patch(path, old, new):
    with open(path) as f:
        content = f.read()
    if old not in content:
        fail(path.split("/")[-1] + " pattern not found")
    with open(path, "w") as f:
        f.write(content.replace(old, new, 1))
    ok("PATCH " + path.split("/")[-1])

# ─────────────────────────────────────────────────────────────────────────────
# FILE 1: SmartAssistUltimateCorrectorEngine.kt
# ─────────────────────────────────────────────────────────────────────────────
write(BASE + "/SmartAssistUltimateCorrectorEngine.kt", """\
package com.assistant.adapter.smartassist

import kotlin.math.hypot

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
 * Corrects every type of eFootball Smart Assist drift:
 *
 *   PASS  - SA deflects toward nearest opponent, NOT intended receiver.
 *            Receiver runs away so the ball goes to empty space.
 *            Fix: predict receiver future position + shift away from SA pull.
 *
 *   SHOT  - SA redirects to a safe zone; ball flies wide even on-target input.
 *            Fix: aim the OPEN post opposite the goalkeeper.
 *
 *   CROSS - SA aims at current receiver position; receiver runs past it,
 *            defender fills the gap, interception every time.
 *            Fix: predict where receiver WILL BE when ball arrives, aim there.
 *
 *   KEEPER - SA positions GK incorrectly, does not track ball path.
 *            Fix: clamp GK to ball horizontal path inside goal width.
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
        val dist = hypot((intendedX - ballX).toDouble(), (intendedY - ballY).toDouble()).toFloat()
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
        val goalCX = if (goalDetected) (goalLeftX + goalRightX) * 0.5f else 1650f
        val goalCY = if (goalDetected) (goalTopY + goalBottomY) * 0.5f else ballY
        val dist = hypot((ballX - goalCX).toDouble(), (ballY - goalCY).toDouble()).toFloat()
        if (dist > MAX_SHOT_DIST) return null
        val openX: Float
        val openY: Float = goalCY
        if (goalDetected && goalkeeperVisible && goalkeeperX > 0f) {
            openX = if (goalkeeperX <= goalCX)
                (goalCX + (goalRightX - goalCX) * 0.70f).coerceIn(goalLeftX, goalRightX)
            else
                (goalCX - (goalCX - goalLeftX) * 0.70f).coerceIn(goalLeftX, goalRightX)
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
        val goalMidY = if (goalTopY > 0f && goalBottomY > goalTopY)
            (goalTopY + goalBottomY) * 0.5f else ballY
        val gl = goalLeftX.coerceAtLeast(0f)
        val gr = goalRightX.coerceAtMost(1650f).coerceAtLeast(gl)
        val interceptX = ballX.coerceIn(gl, gr)
        return SmartAssistCorrectionResult(interceptX, goalMidY, 0.92f, CorrectionType.KEEPER, true)
    }
}
""")

# ─────────────────────────────────────────────────────────────────────────────
# FILE 2: SmartAssistUltimateCorrectorContributor.kt
# ─────────────────────────────────────────────────────────────────────────────
write(CONTRIB + "/SmartAssistUltimateCorrectorContributor.kt", """\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.SmartAssistUltimateCorrectorEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * SmartAssistUltimateCorrectorContributor
 *
 * Always-on SA drift correction for every action type.
 * Decision tree per frame:
 *   hasBall + goalDetected + within 720px  -> SHOT  correction (open post)
 *   hasBall + viable pass lane             -> PASS  correction (run prediction + anti-drift)
 *   hasBall + viable cross lane            -> CROSS correction (receiver run lead)
 *   !hasBall + goalkeeper visible          -> KEEPER correction (ball-path intercept)
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
        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }

        // SHOT
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

        // PASS
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            val players = scene?.trackedPlayers.orEmpty()
            val receiver = players
                .filter { it.isUserTeam && !it.isGoalkeeper }
                .minByOrNull {
                    hypot(
                        (it.x - frame.passTargetX).toDouble(),
                        (it.y - frame.passTargetY).toDouble()
                    )
                }
            val opponent = players
                .filter { !it.isUserTeam }
                .minByOrNull {
                    hypot(
                        (it.x - frame.passTargetX).toDouble(),
                        (it.y - frame.passTargetY).toDouble()
                    )
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
            val authority = (c.correctionStrength * frame.bestLaneConfidence
                .coerceAtLeast(0.4f)).coerceIn(0f, 1f)
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

        // CROSS
        if (frame.hasBall && frame.viableLaneCount > 0 && frame.bestLaneConfidence > 0f) {
            val players = scene?.trackedPlayers.orEmpty()
            val receiver = players
                .filter { it.isUserTeam && !it.isGoalkeeper }
                .minByOrNull {
                    hypot(
                        (it.x - frame.passTargetX).toDouble(),
                        (it.y - frame.passTargetY).toDouble()
                    )
                }
            val goalCX = if (frame.goalDetected)
                (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
            val goalCY = if (frame.goalDetected)
                (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY
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

        // KEEPER
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
""")

# ─────────────────────────────────────────────────────────────────────────────
# FILE 3: TrueShotContributor.kt
# ─────────────────────────────────────────────────────────────────────────────
write(CONTRIB + "/TrueShotContributor.kt", """\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.TrueShotEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

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
""")

# ─────────────────────────────────────────────────────────────────────────────
# FILE 4: TrueCrossContributor.kt
# ─────────────────────────────────────────────────────────────────────────────
write(CONTRIB + "/TrueCrossContributor.kt", """\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.TrueCrossEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

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
""")

# ─────────────────────────────────────────────────────────────────────────────
# FILE 5: TruePassContributor.kt  (REWRITE)
# ─────────────────────────────────────────────────────────────────────────────
write(CONTRIB + "/TruePassContributor.kt", """\
package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.TrueTargetPassingEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * TruePassContributor  (rewritten)
 *
 * BUG FIXED: old code passed bestLaneConfidence * 10f as retention argument
 * to TrueTargetPassingEngine.optimize(), causing 10x overshoot past the
 * target player on every single pass.
 *
 * NEW BEHAVIOUR:
 *   - When a user receiver is found within 120px of the pass target:
 *       uses TrueTargetPassingEngine.optimizeWithRunPrediction() which
 *       predicts where the receiver will be when the ball arrives and aims
 *       there — defeating SA interception where the receiver runs AWAY
 *       from the ball path.
 *   - Otherwise:
 *       uses TrueTargetPassingEngine.optimize() with retention clamped
 *       correctly to [0, 1].
 */
object TruePassContributor : GameplayContributor {
    override val engineName = "TruePass"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val receiver = scene?.trackedPlayers
            ?.filter { it.isUserTeam && !it.isGoalkeeper }
            ?.minByOrNull {
                hypot(
                    (it.x - frame.passTargetX).toDouble(),
                    (it.y - frame.passTargetY).toDouble()
                )
            }

        val r = if (receiver != null &&
            hypot(
                (receiver.x - frame.passTargetX).toDouble(),
                (receiver.y - frame.passTargetY).toDouble()
            ) < 120.0
        ) {
            TrueTargetPassingEngine.optimizeWithRunPrediction(
                frame.ballX, frame.ballY,
                receiver.x, receiver.y,
                receiver.velocityX, receiver.velocityY
            )
        } else {
            TrueTargetPassingEngine.optimize(
                frame.ballX, frame.ballY,
                frame.passTargetX, frame.passTargetY,
                frame.bestLaneConfidence.coerceIn(0f, 1f)
            )
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = r.correctedX.coerceAtLeast(0f),
            targetY = r.correctedY.coerceAtLeast(0f),
            authority = ((1f - r.interceptionRisk) *
                frame.bestLaneConfidence.coerceAtLeast(0.3f)).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
""")

# ─────────────────────────────────────────────────────────────────────────────
# PATCH 6: ShootingLaneAnalysisEngine.kt  viable 0.40 -> 0.12
# ─────────────────────────────────────────────────────────────────────────────
patch(
    BASE + "/ShootingLaneAnalysisEngine.kt",
    "viable = !lane.blocked && confidence >= 0.40f",
    "viable = !lane.blocked && confidence >= 0.12f"
)

# ─────────────────────────────────────────────────────────────────────────────
# PATCH 7: TrueTargetPassingEngine.kt  clamp + add optimizeWithRunPrediction
# ─────────────────────────────────────────────────────────────────────────────
patch(
    BASE + "/TrueTargetPassingEngine.kt",
    (
        "    fun optimize(\n"
        "        startX: Float, startY: Float,\n"
        "        endX: Float,   endY: Float,\n"
        "        retention: Float\n"
        "    ): PassingAssistResult {\n"
        "        val dx = endX - startX\n"
        "        val dy = endY - startY\n"
        "        // FIX: old formula endX+(dx*0.65f*retention) overshot past receiver.\n"
        "        // Correct: arrive proportionally at startX + dx * retention.\n"
        "        return PassingAssistResult(\n"
        "            correctedX       = (startX + dx * retention).coerceIn(0f, 1650f),\n"
        "            correctedY       = (startY + dy * retention).coerceIn(0f, 720f),\n"
        "            interceptionRisk = (1f - retention).coerceIn(0f, 1f)\n"
        "        )\n"
        "    }"
    ),
    (
        "    /**\n"
        "     * @param retention 0..1 fraction along (start->end) to land on.\n"
        "     * BUG FIX: retention now clamped. Old callers passed * 10f -> 10x overshoot.\n"
        "     */\n"
        "    fun optimize(\n"
        "        startX: Float, startY: Float,\n"
        "        endX: Float,   endY: Float,\n"
        "        retention: Float\n"
        "    ): PassingAssistResult {\n"
        "        val r = retention.coerceIn(0f, 1f)\n"
        "        val dx = endX - startX\n"
        "        val dy = endY - startY\n"
        "        return PassingAssistResult(\n"
        "            correctedX       = (startX + dx * r).coerceIn(0f, 1650f),\n"
        "            correctedY       = (startY + dy * r).coerceIn(0f, 720f),\n"
        "            interceptionRisk = (1f - r).coerceIn(0f, 1f)\n"
        "        )\n"
        "    }\n"
        "\n"
        "    /**\n"
        "     * Predict where receiver will be when ball arrives, aim there.\n"
        "     * Defeats SA interception where receiver runs away from ball path.\n"
        "     */\n"
        "    fun optimizeWithRunPrediction(\n"
        "        ballX: Float, ballY: Float,\n"
        "        receiverX: Float, receiverY: Float,\n"
        "        receiverVx: Float, receiverVy: Float\n"
        "    ): PassingAssistResult {\n"
        "        val dist = kotlin.math.hypot(\n"
        "            (receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()\n"
        "        ).toFloat()\n"
        "        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)\n"
        "        val fps = 60f\n"
        "        val predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, 1650f)\n"
        "        val predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, 720f)\n"
        "        return optimize(ballX, ballY, predX, predY, 1.0f)\n"
        "    }"
    )
)

# ─────────────────────────────────────────────────────────────────────────────
# PATCH 8: RuntimeCoordinator.kt  register 3 new contributors (total -> 29)
# ─────────────────────────────────────────────────────────────────────────────
patch(
    BASE + "/RuntimeCoordinator.kt",
    (
        "            // BATCH 4: instant intercept + build-up press + ball retention shield\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.InstantInterceptContributor)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.BuildUpPressContributor)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor)"
    ),
    (
        "            // BATCH 4: instant intercept + build-up press + ball retention shield\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.InstantInterceptContributor)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.BuildUpPressContributor)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor)\n"
        "            // BATCH S: TrueShot + TrueCross + SA Ultimate Corrector (#27-29)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.TrueShotContributor)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.TrueCrossContributor)\n"
        "            com.assistant.runtime.GameplayEngineRegistry.register(\n"
        "                com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor)"
    )
)

print("")
print("=" * 55)
print("ALL 8 DONE")
print("  FILE   SmartAssistUltimateCorrectorEngine.kt")
print("  FILE   SmartAssistUltimateCorrectorContributor.kt")
print("  FILE   TrueShotContributor.kt")
print("  FILE   TrueCrossContributor.kt")
print("  FILE   TruePassContributor.kt  (rewrite)")
print("  PATCH  ShootingLaneAnalysisEngine.kt  viable 0.40->0.12")
print("  PATCH  TrueTargetPassingEngine.kt     clamp + run predict")
print("  PATCH  RuntimeCoordinator.kt          29 contributors")
print("=" * 55)
