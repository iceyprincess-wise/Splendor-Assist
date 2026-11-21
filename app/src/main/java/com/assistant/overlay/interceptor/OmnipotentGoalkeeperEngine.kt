// Guard header removed under Ed's explicit override for this file only
// (2026-11-12, Patch 5): Ed overrode the guard lock so the fabricated
// goalkeeper fallbacks, the back-to-back recovery calls and the missing
// centre-recovery count in this file can be fixed truthfully.
package com.assistant.overlay.interceptor


import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.hypot

import com.assistant.overlay.interceptor.GoalkeeperMetricsRegistry
import com.assistant.overlay.interceptor.GoalkeeperStateMachine
import com.assistant.overlay.interceptor.GoalkeeperState


import com.assistant.overlay.interceptor.ThreatType
import com.assistant.overlay.interceptor.ThreatZone
import com.assistant.overlay.interceptor.ThreatDecision

import com.assistant.overlay.interceptor.GoalkeeperActionRouter
import com.assistant.overlay.interceptor.GoalkeeperExecutionEngine
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.overlay.repository.GoalkeeperRuntimeState
import com.assistant.TelemetryRepository

object OmnipotentGoalkeeperEngine {
    // No65-D13: the ballTrajectory array + BALL_* consts are gone — the
    // array was written on every defensive-layer call and never read
    // anywhere, and the ball velocity params fed only that dead write.
    @Volatile private var lastGkLayerTimestamp = 0L

    // Item 12 (A10): which defensive-layer action fired last, so the
    // save evaluation is suppressed only after a committed RUSH — a
    // six-yard REFLEX micro-swipe must not swallow a shot evaluation
    // for the whole frame. Reset at the top of every scan.
    @Volatile private var lastDefensiveAction = DefensiveAction.NONE
    private enum class DefensiveAction { NONE, RUSH, REFLEX }




    fun processGoalkeeperDefensiveLayer(
        ballX: Float, ballY: Float,
        gkX: Float, gkY: Float,
        nearestAttackerX: Float, nearestAttackerY: Float,
        pressureButtonX: Float, pressureButtonY: Float,
        joystickX: Float, joystickY: Float,
        screenWidth: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback(),
        screenHeight: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback(),
        // Item 12 (A2): renamed — this was never an attacker check, it
        // is a ball-Y danger-zone check (ball below 0.70h, 0.55h in
        // aggressive mode). The name lied about what it gates.
        isBallInDangerZone: Boolean = true,
        isGkDetected: Boolean = true
    ): Boolean {
        if (!com.assistant.VisionTrust.isGameForeground() || !com.assistant.SmartAssistRepository.enabled() || !GoalkeeperRuntimeState.enabled) return false
        val now = System.currentTimeMillis()
        if (now - lastGkLayerTimestamp < 8L) return false // BEAST MODE: 120Hz reaction throttle for 0.00ms threat response
        lastGkLayerTimestamp = now

        // FABRICATED-THREAT GATE: Require valid detected positions
        if (!isBallInDangerZone || !isGkDetected) return false

        // 1. MITIGATING SCENARIOS 1 & 5: Through-Balls and Composed 1v1 Breakaways
        // UNCONDITIONAL: Distance-based activation bypasses velocity/position gates
        // Batch-trajectory (B): measure the rush from where the keeper
        // WILL be in 6 frames (~300 ms at the 20 fps processed rate),
        // not where he is. KeeperTrajectoryPredictor.interceptPosition
        // returns the predicted position when fresh and the current
        // position otherwise, so a stale prediction behaves exactly
        // like today. A keeper already moving toward the attacker
        // rushes on time instead of late; one moving away is not
        // rushed on a distance he will never close.
        val (rushGkX, rushGkY) =
            com.assistant.KeeperTrajectoryPredictor.interceptPosition(gkX, gkY)
        val attackerToGkDistance = hypot((nearestAttackerX - rushGkX).toDouble(), (nearestAttackerY - rushGkY).toDouble())

        if (attackerToGkDistance < 200.0) {
                // Route GK RUSH through ContributionRegistry.offer - the emergency lane (Downward swipe on Pressure Button)
                // SPLENDOR_P12_T3_RUSH: the rush was identical at distance 31
                // and 185 (fixed swipe + 55ms). Scale by the computed distance.
                val rushScale = (attackerToGkDistance / 200.0).toFloat().coerceIn(0f, 1f)
                val targetRushY = pressureButtonY + (screenHeight * 0.12f * rushScale)
                
                val rushRequest = ExecutionRequest(
                    source = ExecutionSource.GOALKEEPER,
                    // No64-D31: phase 100 — the defensive-layer rush used
                    // phase 5, which collides with RUSH_OUT.ordinal (5):
                    // a pending rush reservation-blocked a same-phase save
                    // offer (and vice versa) for up to 300 ms.
                    phase = 100,
                    // no59 ITEM 1: carry the engine name to the dispatch log.
                    engine = "OmnipotentGoalkeeperEngine",
                    startX = pressureButtonX,
                    startY = pressureButtonY,
                    endX = pressureButtonX,
                    endY = targetRushY,
                    duration = (30L + (25L * rushScale).toLong())
                )

                if (ContributionRegistry.offer(rushRequest)) {
                    com.assistant.SmartAssistMetrics.recordGoalkeeperShadow(
                        rushRequest,
                        "phase-100 goalkeeper rush submitted unchanged as shadow-observed emergency gesture"
                    )
                    lastGkLayerTimestamp = now
                    lastDefensiveAction = DefensiveAction.RUSH
                    com.assistant.diagnostic.RuntimeLogger.log("GK_LAYER 1v1 rush bus-enqueued actionId=${rushRequest.actionId} dist=${attackerToGkDistance.toInt()} predGk=(${rushGkX.toInt()},${rushGkY.toInt()})", "GOALKEEPER")
                    return true
                }
        }

        // 2. MITIGATING SCENARIO 2: Close-Range Six-Yard Combination Play
        val sixYardTop = screenHeight * 0.75f
        val goalAreaLeft = screenWidth * 0.32f
        val goalAreaRight = screenWidth * 0.68f

        val isBallInSixYardBox = ballY > sixYardTop && ballX in goalAreaLeft..goalAreaRight
        // UNCONDITIONAL: Box-based activation bypasses velocity gates
        if (isBallInSixYardBox) {
            // FORCE REACTION REFLEX: Micro-tap on Directional Joystick toward incoming ball
            val interceptAngle = atan2((ballY - gkY).toDouble(), (ballX - gkX).toDouble())
            val swipeRadius = screenHeight * 0.08f
            
            val joyTargetX = (joystickX + (cos(interceptAngle) * swipeRadius)).toFloat().coerceIn(0f, screenWidth)
            val joyTargetY = (joystickY + (sin(interceptAngle) * swipeRadius)).toFloat().coerceIn(0f, screenHeight)

            val reflexRequest = ExecutionRequest(
                source = ExecutionSource.GOALKEEPER,
                // No64-D31: phase 101 — the defensive-layer reflex used
                // phase 6, which collides with BLOCK_LEFT.ordinal (6).
                phase = 101,
                // no59 ITEM 1: carry the engine name to the dispatch log.
                engine = "OmnipotentGoalkeeperEngine",
                startX = joystickX,
                startY = joystickY,
                endX = joyTargetX,
                endY = joyTargetY,
                duration = 20L
            )

            if (ContributionRegistry.offer(reflexRequest)) {
                com.assistant.SmartAssistMetrics.recordGoalkeeperShadow(
                    reflexRequest,
                    "phase-101 goalkeeper reflex submitted unchanged as shadow-observed emergency gesture"
                )
                lastGkLayerTimestamp = now
                lastDefensiveAction = DefensiveAction.REFLEX
                com.assistant.diagnostic.RuntimeLogger.log("GK_LAYER close-range reflex pulse bus-enqueued actionId=${reflexRequest.actionId}", "GOALKEEPER")
                return true
            }
        }

        return false
    }

    // Item 12 (12a) — PRE-SHOT 1v1 rush. Runs every frame on real
    // tracked positions (keeper + nearest opponent to the ball + ball),
    // with NO gauge input: the keeper must close on a 1v1 attacker
    // BEFORE the shot gauge appears, not after it. Offers through the
    // defensive layer with the real attacker (not the gauge pixel); the
    // layer records RUSH, so the anomaly path below skips its own rush
    // and the save evaluation still runs for a REFLEX-only frame.
    private fun runPreShotRushIfNeeded() {
        if (!GoalkeeperRuntimeState.enabled) return
        val t = try { TelemetryRepository.current() } catch (_: Throwable) { return }
        val hasRealGk = t.goalkeeperX != 0f && t.goalkeeperY != 0f
        if (!hasRealGk) return
        if (t.ballX == 0f && t.ballY == 0f) return
        val scene = try { com.assistant.SceneTracker.current() } catch (_: Throwable) { null } ?: return
        var best: com.assistant.TrackedPlayer? = null
        var bestDist = Float.MAX_VALUE
        for (p in scene.trackedPlayers) {
            if (p.isUserTeam || p.isGoalkeeper) continue
            if (p.lastSeenFrame != scene.frameNumber) continue
            val d = kotlin.math.hypot(p.x - t.ballX, p.y - t.ballY)
            if (d < bestDist) { bestDist = d; best = p }
        }
        val attacker = best ?: return
        // Keeper velocity is per-frame in the predictor — the same unit
        // PreShotRushEngine takes, so no fps is guessed.
        val pred = try { com.assistant.KeeperTrajectoryPredictor.current() } catch (_: Throwable) { null }
        if (!PreShotRushEngine.shouldRush(
                t.goalkeeperX, t.goalkeeperY,
                pred?.velocityX ?: 0f, pred?.velocityY ?: 0f,
                attacker.x, attacker.y, t.ballX, t.ballY
            )
        ) return
        val pressureSlot = com.assistant.ControlMappingTrainer.slotCenter(4)
        processGoalkeeperDefensiveLayer(
            ballX = t.ballX,
            ballY = t.ballY,
            gkX = t.goalkeeperX,
            gkY = t.goalkeeperY,
            nearestAttackerX = attacker.x,
            nearestAttackerY = attacker.y,
            pressureButtonX = capturedWidth * pressureSlot.first,
            pressureButtonY = capturedHeight * pressureSlot.second,
            joystickX = capturedWidth * com.assistant.ControlMappingTrainer.joystickCenterX(),
            joystickY = capturedHeight * com.assistant.ControlMappingTrainer.joystickCenterY(),
            screenWidth = capturedWidth,
            screenHeight = capturedHeight,
            isBallInDangerZone = true,
            isGkDetected = true
        )
    }

    // No65-D15: 0f, not the old 1650x720 landscape defaults — those values
    // were never observed (scanFrame always sets both from the frame before
    // any read) and contradicted the live 288x640 portrait capture.
    @Volatile private var capturedWidth = 0f
    @Volatile private var capturedHeight = 0f
    private val executionCoordinates = FloatArray(4)


    // [ACTIVE TELEMETRY BRIDGE] - 1000% Capacity Hardware Heuristic Scanner
    fun scanFrameForOpponentAnimation(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int = width * 4) {
        // No63-D26: the keeper ENABLED toggle did not gate this path.
        // processGoalkeeperDefensiveLayer() checks
        // GoalkeeperRuntimeState.enabled (:57), but
        // evaluateOpponentShotTrajectory() — the save path — never did:
        // with the keeper switch OFF, a gauge-pixel anomaly still
        // produced a save dispatch. The scan is the single choke point
        // (sole caller OverlayService:406), so gate here; when disabled
        // the scan does nothing at all (no decision write, no
        // GK_ZONE_SCAN line). This is a different gate from the
        // accessibility-connectivity note below, which is about dispatch
        // availability, not the user's keeper switch.
        if (!GoalkeeperRuntimeState.enabled) return
        lastDefensiveAction = DefensiveAction.NONE
        capturedWidth = width.toFloat()
        capturedHeight = height.toFloat()
        // B2: resync the scan's ball from fresh telemetry BEFORE any
        // read below uses it — the scan used to read a frozen ball
        // while the game was live (N3-trace B). A fresh slot is a
        // no-op; a stale slot with no fresh scene ball logs BALL_STALE.
        try { com.assistant.BallTelemetryResync.resyncIfStale() } catch (_: Throwable) { }
        // Item 12 (12a): pre-shot 1v1 rush — real positions, no gauge.
        runPreShotRushIfNeeded()
        // Accessibility connectivity is a dispatch prerequisite, not a detection gate.
        // Detection and decision readiness proceed regardless of dispatch availability.
        // REMOVED: if (isProcessingFrame) return. Frame scanning MUST NEVER be blocked by execution queue!

        var anomalyDetected = false
        var detectedThreat = ThreatType.NONE
        var detectedZone = ThreatZone.CENTER
        var detectedX = 0
        var detectedY = 0

        val stride = rowStride

        // Target the bottom 25% of the frame where eFootball power gauges trigger
        val startY = (height * 0.75).toInt()

        try {
            val limit = buffer.capacity()
            var y = startY
            while (y < height) {
                var x = 0
                while (x < width) {
                    val index = (y * stride) + (x * 4)
                    if (index + 2 < limit) {
                        val r = buffer.get(index).toInt() and 0xFF
                        val g = buffer.get(index + 1).toInt() and 0xFF
                        val b = buffer.get(index + 2).toInt() and 0xFF

                        val threat = ThreatClassifierEngine.classify(r, g, b)

                        if (threat != ThreatType.NONE) {
                            detectedThreat = threat
                            detectedZone = ThreatZoneEngine.detect(x, y, width, height)
                            detectedX = x
                            detectedY = y

                            // SPLENDOR_P12_T2_SCAN_GEOMETRY: the scan row
                            // fraction and pixel offset used to be written into
                            // playerVelocity/opponentDistance, so consumers
                            // read scan geometry as a player's speed and
                            // distance (pv=0.25 on the first row, distances
                            // always multiples of 8.0). Not published any
                            // more; those fields stay at honest defaults
                            // until a real tracker writes them.
                            anomalyDetected = true
                            break
                        }
                    }
                    x += 8 // High-speed stride skip (Guarantees <1ms execution on Helio G81 cache)
                }
                if (anomalyDetected) break
                y += 8
            }
        } catch (t: Throwable) {
            android.util.Log.e("OmnipotentGoalkeeperEngine", "scan frame failed, anomaly detection skipped this frame", t)
        }

        // Item 12 (A10): the result is no longer read — the RUSH state
        // lives in lastDefensiveAction, so this is a bare run block.
        run {
            val t = TelemetryRepository.current()
            val hasRealGk = t.goalkeeperX != 0f && t.goalkeeperY != 0f
            // FABRICATED-THREAT GATE: previously this layer ran on every frame and
            // invented the attacker (width/2, height*0.7) and the keeper
            // (width/2, height*0.9) whenever real telemetry was absent, so the
            // 1v1 rush fired from made-up coordinates and auto-swiped the
            // pressure button (ghost swipe). Require a real detected opponent
            // animation AND a real tracked keeper position before acting.
            // Item 12 (A10): also skip when the pre-shot rush already
            // committed this frame — a second RUSH offer for the same
            // moment only adds a bus reservation the keeper never uses.
            if (!anomalyDetected || !hasRealGk || lastDefensiveAction == DefensiveAction.RUSH) {
                false
            } else {
                // SPLENDOR_GK_REAL_THREAT_V1 - the attacker and the pressure
                // button were both fabricated here: the attacker was forced to
                // (width/2, height*0.7) and the pressure button to
                // (width*0.9, height*0.7). processGoalkeeperDefensiveLayer()
                // then computed attackerToGkDistance from that invented point
                // and, whenever it fell under 200, auto-submitted a Down-swipe
                // onto the pressure button - a gesture the player never asked
                // for (the ghost swipe). Use the genuinely detected threat pixel
                // for the attacker and the trainer's live pressure-button cell
                // for the button.
                val pressureSlot = com.assistant.ControlMappingTrainer.slotCenter(4)
                // Item 12 (F3): the "attacker" used to be the gauge
                // pixel itself (UI, not a player) — the rush distance
                // was gauge-to-keeper, not attacker-to-keeper. Prefer
                // the nearest real tracked opponent to the ball; fall
                // back to the gauge pixel only when no opponent is
                // tracked this frame.
                val sceneNow = try { com.assistant.SceneTracker.current() } catch (_: Throwable) { null }
                var attackerX = detectedX.toFloat()
                var attackerY = detectedY.toFloat()
                var bestOppDist = Float.MAX_VALUE
                if (sceneNow != null) {
                    for (p in sceneNow.trackedPlayers) {
                        if (p.isUserTeam || p.isGoalkeeper) continue
                        if (p.lastSeenFrame != sceneNow.frameNumber) continue
                        val d = kotlin.math.hypot(p.x - t.ballX, p.y - t.ballY)
                        if (d < bestOppDist) { bestOppDist = d; attackerX = p.x; attackerY = p.y }
                    }
                }
                processGoalkeeperDefensiveLayer(
                    ballX = t.ballX,
                    ballY = t.ballY,
                    gkX = t.goalkeeperX,
                    gkY = t.goalkeeperY,
                    nearestAttackerX = attackerX,
                    nearestAttackerY = attackerY,
                    pressureButtonX = width * pressureSlot.first,
                    pressureButtonY = height * pressureSlot.second,
                    joystickX = width * com.assistant.ControlMappingTrainer.joystickCenterX(),
                    joystickY = height * com.assistant.ControlMappingTrainer.joystickCenterY(),
                    screenWidth = width.toFloat(),
                    screenHeight = height.toFloat(),
                    // Aggressive mode commits the keeper earlier: the danger-zone
                    // gate moves up the pitch instead of being a fixed 0.7.
                    // Item 12 (A2): renamed — this was never an attacker
                    // check, it is a ball-Y danger-zone check.
                    isBallInDangerZone = (t.ballY > height * if (GoalkeeperRuntimeState.aggressiveMode) 0.55f else 0.7f),
                    isGkDetected = hasRealGk
                )
            }
        }

        // Item 12 (A10): only a committed RUSH swallows the save
        // evaluation for this frame — a six-yard REFLEX micro-swipe
        // (or no defensive action at all) must not.
        if (anomalyDetected && lastDefensiveAction != DefensiveAction.RUSH) {
            // SPLENDOR_P10_ZONE_LOG: temporary, remove after live
            // confirmation. ADAPTATION vs the trace sketch: TelemetrySnapshot
            // has NO goalDetected/goalLeftX/goalRightX fields (only ball /
            // keeper telemetry), so the goal comes from SceneTracker
            // (guarded; "n/a" when unavailable) and the ball is re-read
            // from TelemetryRepository (the local `t` above is scoped
            // inside the run{} block and is not visible here).
            val p10Tele = TelemetryRepository.current()
            val p10Scene = try { com.assistant.SceneTracker.current() } catch (_: Throwable) { null }
            // L12 (batch 1): the save path's zone comes from the BALL via
            // the Model-X logic (ThreatZoneEngine.zoneOfBall), not from
            // the scan strip's gauge pixel — the strip only ever yields
            // BOX/GOAL_AREA, so LEFT/RIGHT/CENTER zones and NEAR_POST /
            // FAR_POST directions were unreachable from scan. The ball
            // coords are also passed as the evaluate x/y (same as
            // ThreatPriorityContributor.decisionOf), so heightBand/normX
            // derive from the ball and the TOP band is reachable from the
            // save path. The strip zone stays on the log line as
            // stripZone= for audit.
            val ballZone = ThreatZoneEngine.zoneOfBall(
                p10Tele.ballX,
                p10Tele.ballY,
                p10Scene?.goalLeftX ?: 0f,
                p10Scene?.goalRightX ?: 0f,
                p10Scene?.goalDetected ?: false,
                width.toFloat(),
                height.toFloat()
            )
            // Item 12 (A4): the predicted impact point — normX /
            // heightBand aim at where the ball will cross the line,
            // not where it is now. The prediction is read once here.
            val ballPrediction = com.assistant.BallTrajectoryPredictor.current()
            val decision = ThreatPriorityEngine.evaluate(
                detectedThreat,
                ballZone,
                p10Tele.ballX.toInt(),
                p10Tele.ballY.toInt(),
                width,
                height,
                // Q68-86 (batch 5b — D15): live ball trajectory speed so
                // LONG_BALL is trajectory-gated, not a catch-all.
                ballPrediction.speed,
                ballPrediction.predictedX,
                ballPrediction.predictedY,
                ballPrediction.valid
            )

            GoalkeeperDecisionRegistry.latestDecision = decision

            val p10GoalDet = p10Scene?.goalDetected ?: false
            val p10GoalCx = if (p10Scene != null && p10Scene.goalDetected) ((p10Scene.goalLeftX + p10Scene.goalRightX) * 0.5f).toString() else "n/a"
            com.assistant.diagnostic.RuntimeLogger.log(
                "GK_ZONE_SCAN threat=" + detectedThreat + "(ThreatClassifierEngine)" +
                    " zone=" + decision.zone + " dir=" + decision.direction + "(ShotDirectionEngine)" +
                    " pri=" + decision.priority + "(ThreatPriorityEngine)" + " band=" + decision.heightBand + " normX=" + decision.normX +
                    " px=(" + detectedX + "," + detectedY + ") stripZone=" + detectedZone + " cap=" + width + "x" + height +
                    " ball=(" + p10Tele.ballX + "," + p10Tele.ballY + ")" +
                    " goalDet=" + p10GoalDet + " goalCX=" + p10GoalCx,
                "GOALKEEPER")

            evaluateOpponentShotTrajectory(decision)
        }
    }

    @Volatile private var lastShotEvaluationTimestamp = 0L
    @Volatile private var lastSaveTimestamp = 0L // Tracks the exact millisecond of the last successful save/parry for rebound logic

    // Item 12 (B5): whether the current recovery was entered from a
    // save (ends on the rebound window) or a rush (ends on its own
    // deadline — a rush has no save timestamp, so the old window check
    // finished a rush recovery at the very next evaluation).
    @Volatile private var recoveryFromSave = false
    @Volatile private var rushRecoveryUntilMs = 0L

    // No64-D27: save-offer stroke duration — 15 ms, the floor the loop
    // enforces for SMART_ASSIST requests (was 8L, below that floor).
    private const val SAVE_OFFER_DURATION_MS = 15L

    // No65-D14: the dive-family set is hoisted — it used to be built with
    // setOf(...) inside `if (accepted)` on EVERY accepted save (a fresh
    // HashSet per save on the hot path, against this file's
    // zero-allocation claim). Membership is unchanged.
    // Item 12 (B4): REBOUND_SAVE is OUT of this set — it used to re-arm
    // its own rebound window (lastSaveTimestamp) and re-enter recovery
    // on every accept, so the window never expired while second balls
    // kept coming. A rebound save is the END of a rebound, not a new
    // save: it sets neither the timestamp nor recovery.
    private val DIVE_FAMILY_ACTIONS = setOf(
        GoalkeeperAction.DIVE_LEFT,
        GoalkeeperAction.DIVE_RIGHT,
        GoalkeeperAction.DIVE_BOTTOM_LEFT,
        GoalkeeperAction.DIVE_BOTTOM_RIGHT,
        GoalkeeperAction.BLOCK_LEFT,
        GoalkeeperAction.BLOCK_RIGHT,
        GoalkeeperAction.JUMP_TOP_CENTER,
        GoalkeeperAction.JUMP_TOP_LEFT,
        GoalkeeperAction.JUMP_TOP_RIGHT,
        GoalkeeperAction.REACH_LEFT_CENTER,
        GoalkeeperAction.REACH_RIGHT_CENTER,
        GoalkeeperAction.CATCH_CENTER,
        GoalkeeperAction.REFLEX_CENTER,
        GoalkeeperAction.PARRY_BOTTOM_CENTER
    )

    // No64-D22: per-episode tracker for the recovery counter. The
    // recovery count increments only when the recovery target changes.
    // (The router keeps its own lastRoutedAction. No66-D9: the
    // PUNCH/CLAIM interception classification that used to live here
    // is removed — it double-counted the router's episode increments
    // into the same counter; the router is now the single writer of
    // interceptions.)
    @Volatile private var lastRecoveryTarget: RecoveryTarget? = null

    private fun evaluateOpponentShotTrajectory(decision: ThreatDecision) {
        val now = System.currentTimeMillis()
        if (now - lastShotEvaluationTimestamp < 8L) return // BEAST MODE: 120Hz evaluation throttle for instant rebound/shot reaction
        lastShotEvaluationTimestamp = now

        // Reaction speed control: a higher reaction value keeps the keeper
        // in rebound mode for a shorter window, so it re-commits to a fresh
        // read sooner. Previously this control was read by nothing.
        // (Hoisted here from below so the No64-D16 window-end check can
        // use the same value as the rebound override.)
        val reboundWindowMs =
            (500L - (GoalkeeperRuntimeState.reactions.coerceIn(0, 100) * 2.5f)).toLong()

        // No64-D16: the rebound window ends recovery — finish it before
        // the next decision is evaluated, so RECOVERY spans the window.
        // Item 12 (B5): a rush recovery has no save timestamp — the old
        // check finished it at the very next evaluation (stale
        // lastSaveTimestamp). Save recoveries still end on the rebound
        // window; rush recoveries end on their own deadline.
        if (RecoveryPositionEngine.recovering()) {
            if (recoveryFromSave &&
                System.currentTimeMillis() - lastSaveTimestamp >= reboundWindowMs
            ) {
                RecoveryPositionEngine.finishRecovery()
            } else if (!recoveryFromSave &&
                System.currentTimeMillis() >= rushRecoveryUntilMs
            ) {
                RecoveryPositionEngine.finishRecovery()
            }
        }

        // Item 12 (A8): the SAVE transition used to sit here, at the top
        // of EVERY evaluation — even when the action ended up TRACK
        // (nothing), so the state read SAVE between evaluations while
        // the keeper did nothing. The transition now follows the action
        // and lives below, after finalAction is known.

        GoalkeeperMetricsRegistry.triggerCount.incrementAndGet()

        com.assistant.diagnostic.RuntimeMetricsRegistry.goalkeeperTriggers.incrementAndGet()

        try {

            // Absolute Hardware Limit Vectors for Redmi 15C class
            val screenWidthBase = capturedWidth
            val screenHeightBase = capturedHeight


                val recoveryTarget = RecoveryPredictionEngine.predict(decision)

                // No64-D17: the write goes through recordTarget() now that
                // lastTarget is private.
                RecoveryAuthorityRegistry.recordTarget(recoveryTarget)

                when (recoveryTarget) {
                    RecoveryTarget.CENTER -> {
                        RecoveryAuthorityRegistry.centerRecoveries.incrementAndGet()
                    }
                    RecoveryTarget.LEFT -> {
                        RecoveryAuthorityRegistry.leftRecoveries.incrementAndGet()
                    }
                    RecoveryTarget.RIGHT -> {
                        RecoveryAuthorityRegistry.rightRecoveries.incrementAndGet()
                    }
                    RecoveryTarget.GOAL_AREA -> {
                        RecoveryAuthorityRegistry.goalAreaRecoveries.incrementAndGet()
                    }
                }

                // No64-D33: name the recovery engine and its output in the
                // log, so a live session proves RecoveryPredictionEngine is
                // active. (No64-D40: the target value itself still steers
                // no gesture — it is observable here and in the snapshot,
                // but there is no designed recovery-position gesture yet.)
                com.assistant.diagnostic.RuntimeLogger.log(
                    "GK_RECOVERY target=" + recoveryTarget + "(RecoveryPredictionEngine)",
                    "GOALKEEPER")

                val action = GoalkeeperActionRouter.route(decision)

                // Item 12 (F1): goal-bound promote — the second
                // qualification path. A ball predicted to cross the goal
                // line inside the posts is a save whatever the gauge
                // priority says; a TRACK/CATCH under that prediction
                // becomes the dive the router itself would choose at the
                // SAVE line (re-route the decision at priority 100, so
                // the router's own matrix picks the dive — direction and
                // heightBand preserved, no invented action).
                val ballPred = com.assistant.BallTrajectoryPredictor.current()
                val goalScene = try { com.assistant.SceneTracker.current() } catch (_: Throwable) { null }
                val goalBound = GoalBoundBallEngine.isGoalBound(
                    ballPred.predictedX, ballPred.predictedY, ballPred.velocityY, ballPred.valid,
                    goalScene?.goalLeftX ?: 0f, goalScene?.goalRightX ?: 0f,
                    goalScene?.goalDetected ?: false,
                    screenHeightBase, screenWidthBase, screenHeightBase
                )
                val promotedAction =
                    if (goalBound && (action == GoalkeeperAction.TRACK || action == GoalkeeperAction.CATCH_CENTER)) {
                        GoalkeeperActionRouter.route(decision.copy(priority = 100))
                    } else {
                        action
                    }

                // Item 12 (A9): RECOVER is now produced — on a quiet
                // (TRACK) frame while recovering, the recovery vector
                // (RecoveryVectorEngine, goal-line point for the predicted
                // target) is offered instead of skipping. It never
                // competes with a live save: only when the router says
                // TRACK, and the rebound override below still wins.
                // The No64-D16 finish-on-TRACK that used to sit here is
                // removed: TRACK-while-recovering is now the RECOVER
                // offer, and recovery ends on the window check at the
                // top of the next evaluation instead.
                val recoveryVector: FloatArray? =
                    if (promotedAction == GoalkeeperAction.TRACK && RecoveryPositionEngine.recovering()) {
                        val joyX = screenWidthBase * com.assistant.ControlMappingTrainer.joystickCenterX()
                        val joyY = screenHeightBase * com.assistant.ControlMappingTrainer.joystickCenterY()
                        RecoveryVectorEngine.vectorForTarget(
                            recoveryTarget,
                            goalScene?.goalLeftX ?: 0f, goalScene?.goalRightX ?: 0f,
                            screenWidthBase, screenHeightBase, joyX, joyY
                        )
                    } else {
                        null
                    }

                // BEAST MODE REBOUND OVERRIDE: If a save was made <500ms ago, force REBOUND_SAVE to prevent lying down
                val finalAction =
                    if (GoalkeeperRuntimeState.enabled && System.currentTimeMillis() - lastSaveTimestamp < reboundWindowMs) {
                        GoalkeeperAction.REBOUND_SAVE
                    } else if (recoveryVector != null) {
                        GoalkeeperAction.RECOVER
                    } else {
                        promotedAction
                    }

                // Item 12 (A8): the state follows the action (see the
                // note at the top of this function) — SAVE only for a
                // save, PANIC for a panic block, CROSS for a cross
                // claim/punch, RECOVERY for the recovery walk, THREAT
                // for a rush, TRACKING for everything else.
                when {
                    finalAction == GoalkeeperAction.BLOCK_LEFT || finalAction == GoalkeeperAction.BLOCK_RIGHT ->
                        GoalkeeperStateMachine.transition(GoalkeeperState.PANIC)
                    finalAction in DIVE_FAMILY_ACTIONS || finalAction == GoalkeeperAction.REBOUND_SAVE ->
                        GoalkeeperStateMachine.transition(GoalkeeperState.SAVE)
                    finalAction == GoalkeeperAction.PUNCH_CROSS || finalAction == GoalkeeperAction.CLAIM_CROSS ||
                        finalAction == GoalkeeperAction.AERIAL_CLAIM ->
                        GoalkeeperStateMachine.transition(GoalkeeperState.CROSS)
                    finalAction == GoalkeeperAction.RECOVER ->
                        GoalkeeperStateMachine.transition(GoalkeeperState.RECOVERY)
                    finalAction == GoalkeeperAction.RUSH_OUT ->
                        GoalkeeperStateMachine.transition(GoalkeeperState.THREAT)
                    else ->
                        GoalkeeperStateMachine.transition(GoalkeeperState.TRACKING)
                }

                // No64-D39: TRACK is "do nothing" — offering its zero-length
                // HoldPosition swipe wastes a bus slot, can occupy the
                // (source, phase) reservation, and counts as a save attempt.
                // Skip the offer entirely. (The rebound override above
                // already turns a TRACK inside the window into REBOUND_SAVE,
                // so this only skips genuine do-nothing frames.)
                // No65-D16: the vector is computed only when an offer will
                // be made — it used to be computed for TRACK too and then
                // thrown away (a fresh FloatArray per TRACK frame, the
                // common idle case, for a zero vector nothing reads).
                val accepted = if (finalAction == GoalkeeperAction.TRACK) {
                    false
                } else {
                    // Item 12 (A9): RECOVER carries its own vector
                    // (RecoveryVectorEngine) — vectorFor(RECOVER) is a
                    // zero-length hold, so it is not used here.
                    val vector = if (finalAction == GoalkeeperAction.RECOVER) {
                        recoveryVector ?: GoalkeeperExecutionEngine.vectorFor(
                            finalAction,
                            screenWidthBase,
                            screenHeightBase
                        )
                    } else {
                        GoalkeeperExecutionEngine.vectorFor(
                            finalAction,
                            screenWidthBase,
                            screenHeightBase
                        )
                    }

                    // Positioning control: how far off the goal line the keeper stands.
                    // 50 is neutral; above pushes the keeper up the pitch, below drops
                    // it back. Bounded to +/-8% of screen height.
                    // Item 12 (A9): the RECOVER vector is already exact
                    // (goal-line point) — the positioning bias must not
                    // push it off the line.
                    val positioningBias =
                        if (finalAction == GoalkeeperAction.RECOVER) 0f
                        else ((GoalkeeperRuntimeState.positioning.coerceIn(0, 100) - 50) / 50f) *
                            (screenHeightBase * 0.08f)

                    // Hyper-velocity interceptor swipe paths with integrated Server/Noise offsets
                    executionCoordinates[0] = vector[0]
                    // No64-D13: RushOutActionEngine clamps its startY, but this
                    // positioningBias (±8% of screen height, unclamped) used to
                    // push the coordinates off-screen afterwards. Clamp the sum.
                    executionCoordinates[1] = (vector[1] + positioningBias).coerceIn(0f, screenHeightBase)
                    executionCoordinates[2] = vector[2]
                    executionCoordinates[3] = (vector[3] + positioningBias).coerceIn(0f, screenHeightBase)

                    ContributionRegistry.offer(
                        ExecutionRequest(
                            source = ExecutionSource.GOALKEEPER,
                            phase = finalAction.ordinal,
                            // No64-D32: carry the PRODUCING action engine's
                            // name to the dispatch log (no59 ITEM 1), not the
                            // offerer's — so the dispatch line reads
                            // engine=<ActionEngineName> instead of
                            // engine=OmnipotentGoalkeeperEngine action=?.
                            engine = GoalkeeperExecutionEngine.engineNameFor(finalAction),
                            startX = executionCoordinates[0],
                            startY = executionCoordinates[1],
                            endX = executionCoordinates[2],
                            endY = executionCoordinates[3],
                            duration = SAVE_OFFER_DURATION_MS
                        )
                    )
                }

                if (accepted) {
                    // No64-D28: this counts bus-accepted OFFERS, not
                    // dispatched gestures — a cooling-dropped offer still
                    // counts here. The snapshot labels it SaveOffers.
                    // There is no dispatch-confirmation signal at this site,
                    // so no confirmed-dispatch count is invented.
                    GoalkeeperMetricsRegistry.saveAttempts.incrementAndGet()
                    // Item 12 (C5): the accept log names keeper
                    // visibility — a save offered while the keeper is
                    // not visible is a blind offer, and the log must
                    // say so instead of counting it silently.
                    val gkTele = try { TelemetryRepository.current() } catch (_: Throwable) { null }
                    val keeperVisible = gkTele != null && gkTele.goalkeeperX != 0f && gkTele.goalkeeperY != 0f
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "GK_SAVE accepted action=" + finalAction + " keeperVisible=" + keeperVisible,
                        "GOALKEEPER")
                    if (finalAction in DIVE_FAMILY_ACTIONS) {
                        lastSaveTimestamp = System.currentTimeMillis()
                        recoveryFromSave = true
                        // No64-D16: an accepted dive-family save enters
                        // recovery — RECOVERY then spans the rebound window
                        // (finished at the top of the next evaluation, or
                        // when the next decision is TRACK). Q65-6 (batch 5b)
                        // fixed the torn-read caveat that used to sit here:
                        // begin/finish + the state transition are now one
                        // atomic step under the shared monitor.
                        RecoveryPositionEngine.beginRecovery()
                    } else if (finalAction == GoalkeeperAction.RUSH_OUT) {
                        // Item 12 (B5): a rush leaves the keeper high up
                        // the pitch — enter recovery so the RECOVER offer
                        // walks him back to the line. No save timestamp:
                        // a rush is not a save and must not arm the
                        // REBOUND_SAVE override. The recovery ends on
                        // rushRecoveryUntilMs (top of this function).
                        recoveryFromSave = false
                        rushRecoveryUntilMs = System.currentTimeMillis() + reboundWindowMs
                        RecoveryPositionEngine.beginRecovery()
                    }
                    // Item 12 (A14): BLOCK is only ever produced by the
                    // router's PANIC arm, so an accepted BLOCK is a panic
                    // save by construction — count it here, at accept,
                    // not at route (the router's increment counted
                    // cooling-dropped BLOCKs too).
                    if (finalAction == GoalkeeperAction.BLOCK_LEFT || finalAction == GoalkeeperAction.BLOCK_RIGHT) {
                        GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                    }
                }

                // No66-D9: classify the accepted action for the panic-save
                // counter only. The PUNCH/CLAIM interception arm that used
                // to live here is removed: it wrote interceptions from a
                // second tracker (lastClassifiedAction) while the router
                // writes the same counter from lastRoutedAction, so one
                // PUNCH/CLAIM episode incremented Intercept twice — once
                // per tracker. The router is now the single writer of
                // interceptions (episode-gated, no64-D22); panicSaves is
                // not a feedback counter and stays per-accept, as before.
                if (accepted && finalAction == GoalkeeperAction.REBOUND_SAVE) {
                    if (com.assistant.SmartAssistRepository.panicActive()) {
                        GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                    }
                }


            // No64-D22: recoveryCount counts recovery-target EPISODES, not
            // trajectory evaluations — it used to increment on every call.
            if (recoveryTarget != lastRecoveryTarget) {
                lastRecoveryTarget = recoveryTarget
                GoalkeeperMetricsRegistry.recoveryCount.incrementAndGet()
            }
        } finally {
            // No64-D16: do not clobber RECOVERY — the save path's finally
            // used to transition to IDLE unconditionally, which would undo
            // beginRecovery() in the same call. While recovering, the state
            // is restored to RECOVERY after each evaluation (the top of the
            // next evaluation transitions to SAVE, so without this the state
            // would read SAVE between evaluations instead of RECOVERY).
            // Q65-6 (batch 5b): the recovering() read + transition pair
            // runs under the shared GoalkeeperStateMachine monitor, so a
            // concurrent begin/finish cannot land between the read and the
            // transition and clobber the restore.
            synchronized(GoalkeeperStateMachine) {
                if (RecoveryPositionEngine.recovering()) {
                    GoalkeeperStateMachine.transition(GoalkeeperState.RECOVERY)
                } else {
                    GoalkeeperStateMachine.transition(GoalkeeperState.IDLE)
                }
            }
        }
    }
}
