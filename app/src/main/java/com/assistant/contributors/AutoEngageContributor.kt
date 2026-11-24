package com.assistant.contributors

import com.assistant.BallOwnershipEngine
import com.assistant.BallPathInterceptEngine
import com.assistant.BallTrajectoryPredictor
import com.assistant.ControlMappingTrainer
import com.assistant.UserIntentVision
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.DispatchBounds
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.runtime.*
import com.assistant.vision.CameraProfile
import kotlin.math.hypot

/*
 * AutoEngage — aggressive auto-intercept + silent cursor switch for
 * outfield players: the keeper auto-rush, extended to the defence.
 *
 * WHEN (all from existing frame data, no invented signal):
 * - Arm S (switch): the PlayerSwitch trigger, reused verbatim —
 *   defending (!hasBall), cursor known, best engager (teammate
 *   nearest the ball, keeper excluded) within 60 px
 *   (BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE, referenced not
 *   restated) while the cursor is beyond 60 px. The switch fires
 *   whenever the best man can engage and the cursor cannot.
 * - Arm D (drive): the cursor IS the best engager and the ball is
 *   in flight with a usable cut (BallPathInterceptEngine, from the
 *   CURSOR's position — not defenderId's — so the steered player
 *   and the geometry agree), or the ball/carrier is within 60 px
 *   of the cursor (hold an engagement, never start a cross-pitch
 *   press autonomously — starting a press from distance is
 *   BallPress's job, touch-gated).
 *
 * HOW: direct INTERCEPTION-source offers to ContributionRegistry
 * (emergency lane, like AntiCutback) — NOT contributions. A
 * contribution would go through RDL (source hardcoded SMART_ASSIST,
 * touch-gated) and ConAI arbitration, where a 0.60 switch loses to
 * 0.70-1.00 DEFENDs. The switch is a slot-3 flick: start = slot-3
 * centre, end = start + unit(target - cursor) * radius, radius =
 * dist(target, cursor) coerced to the measured-max-drag ceiling —
 * the same rendering chooseRequest uses (RDL analogDeflectionRadius:
 * distance coerced to DispatchBounds.maxDragPx ?: min(w,h)*0.08f),
 * replicated because that function is RDL-private. The drive is a
 * joystick drag to the cut (or the ball), 16 ms, same shape as
 * InstantIntercept's DEFEND. Switch phase = SWITCH.ordinal (9),
 * drive phase = DEFEND.ordinal (3), matching RDL's convention; the
 * (source, phase) reservation keys differ from RDL's SMART_ASSIST
 * ones, so there is no collision.
 *
 * NEVER-FIGHT: mirrors RDL slotForRequest exactly — switch held
 * while the user holds slot 3, drive held while he holds the
 * joystick (mask bit 0). Both arms gated on !frame.panic
 * (BallPress precedent — the panic toggle is Ed's own switch).
 * The drive is unslewed (RDL's slew limiter is private; direct
 * offers have no slew — AntiCutback precedent). Consecutive drives
 * retarget smoothly because the ball/cut moves smoothly frame to
 * frame; a 1-frame race remains when the user first touches the
 * stick before the hold confirms (at most one 16 ms drag).
 *
 * POLICY: no PLAYER_SWITCH amendment needed — INTERCEPTION is
 * already autonomous (bypass switch ON, user-revocable). The
 * amendment stays open for the standalone PlayerSwitch contributor
 * only. Spam guards copied verbatim from PlayerSwitchContributor:
 * 10-frame switch cooldown, 6-frame verify window, 3 failed
 * attempts per target then suppress until the target changes.
 * Drive offers at most once per 50 ms (one per processed frame).
 * The keeper outranks nothing here: GK and INT share the bus high
 * ring in FIFO order, and a switch/drive touches different
 * controls than a save.
 *
 * FAIL-OPEN: no cursor → no offer; no best engager → no offer;
 * untrained slot 3 → no switch; no cut and ball beyond 60 px →
 * no drive; any fault → null (whole body try/catch). Drives are
 * proven by the dispatch log (engine=AutoEngage); only switch
 * lifecycle events are logged here.
 */
object AutoEngageContributor : GameplayContributor {
    override val engineName = "AutoEngage"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    // Cadence constants — CHOSEN, stated as such; frames are the
    // 20 fps processed rate (PlayerSwitchContributor, same values).
    private const val SWITCH_COOLDOWN_FRAMES = 10L // 500 ms between switches
    private const val VERIFY_WINDOW_FRAMES = 6L // 300 ms to see the cursor move
    private const val MAX_SWITCH_ATTEMPTS = 3 // per target, then suppress
    private const val SWITCH_DURATION_MS = 40L // PlayerSwitch precedent
    private const val DRIVE_DURATION_MS = 16L // InstantIntercept precedent
    private const val DRIVE_MIN_INTERVAL_MS = 50L // one drive per processed frame max

    // Engagement range, referenced from the repo's own possession rule.
    private val engageDistanceSq: Float
        get() = BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE *
            BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE

    // Pending-switch bookkeeping. @Volatile: collect() runs on the
    // capture thread; nothing else writes these. Same shape as
    // PlayerSwitchContributor (its verify/suppress machine, reused).
    @Volatile private var pendingTargetId: Int? = null
    @Volatile private var pendingSinceFrameId: Long = -1L
    @Volatile private var attemptsTargetId: Int? = null
    @Volatile private var attempts: Int = 0
    @Volatile private var suppressedTargetId: Int? = null
    @Volatile private var lastSwitchFrameId: Long = -1L
    @Volatile private var lastDriveMs: Long = 0L

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall && !frame.panic

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        try {
            verifyPendingSwitch(frame)
            if (!frame.trusted || frame.hasBall || frame.panic) return null
            val w = CameraProfile.captureWidthOrFallback()
            val h = CameraProfile.captureHeightOrFallback()
            if (w <= 0f || h <= 0f) return null
            val cursor = frame.cursorId?.let { id ->
                frame.entities.firstOrNull { it.id == id && it.teammate }
            } ?: return null
            val best = frame.entities
                .filter { it.teammate && !it.isGoalkeeper }
                .minByOrNull { distSq(it.x, it.y, frame.ballX, frame.ballY) }
                ?: return null
            val holdingSwitch =
                frame.userIntentMask and UserIntentVision.slotBit(3) != 0
            val holdingStick =
                frame.userIntentMask and UserIntentVision.slotBit(0) != 0
            if (best.id == cursor.id) {
                // Arm D: cursor already the best engager — drive him.
                if (!holdingStick) offerDrive(frame, cursor, w, h)
                return null
            }
            val engageSq = engageDistanceSq
            if (distSq(best.x, best.y, frame.ballX, frame.ballY) > engageSq) return null
            if (distSq(cursor.x, cursor.y, frame.ballX, frame.ballY) <= engageSq) {
                // Cursor can engage — drive him instead of switching.
                if (!holdingStick) offerDrive(frame, cursor, w, h)
                return null
            }
            // Arm S: best can engage, cursor cannot — switch, silently.
            if (holdingSwitch) return null
            if (best.id == suppressedTargetId) return null
            if (pendingTargetId != null) return null
            if (lastSwitchFrameId >= 0L &&
                frame.frameId - lastSwitchFrameId < SWITCH_COOLDOWN_FRAMES
            ) return null
            if (!ControlMappingTrainer.matchActive ||
                !ControlMappingTrainer.isSlotTrained(3)
            ) return null
            if (cursor.x == best.x && cursor.y == best.y) return null
            if (attemptsTargetId != best.id) {
                attempts = 0
                attemptsTargetId = best.id
            }
            val (snx, sny) = ControlMappingTrainer.slotCenter(3)
            val sx = snx * w
            val sy = sny * h
            val dx = best.x - cursor.x
            val dy = best.y - cursor.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (!dist.isFinite() || dist <= 0f) return null
            val ceiling = DispatchBounds.maxDragPx
                ?: (minOf(w, h) * 0.08f)
            val radius = dist.coerceIn(0f, ceiling)
            val ux = dx / dist
            val uy = dy / dist
            val ex = (sx + ux * radius).coerceIn(0f, w - 1f)
            val ey = (sy + uy * radius).coerceIn(0f, h - 1f)
            val offered = ContributionRegistry.offer(
                ExecutionRequest(
                    source = ExecutionSource.INTERCEPTION,
                    phase = ActionClass.SWITCH.ordinal,
                    actionClass = ActionClass.SWITCH,
                    engine = engineName,
                    startX = sx,
                    startY = sy,
                    endX = ex,
                    endY = ey,
                    duration = SWITCH_DURATION_MS
                )
            )
            if (!offered) return null
            pendingTargetId = best.id
            pendingSinceFrameId = frame.frameId
            lastSwitchFrameId = frame.frameId
            RuntimeLogger.log(
                "AUTOENGAGE switch cursor=${cursor.id} -> target=${best.id} " +
                    "attempt=${attempts + 1}",
                "AUTOENGAGE"
            )
            return null
        } catch (_: Throwable) {
            return null
        }
    }

    // Switch verification — PlayerSwitchContributor's machine verbatim:
    // cursor == target inside the window → took; frame-id regression →
    // drop without counting; window expired → one failed attempt, and
    // MAX_SWITCH_ATTEMPTS failures for the same target suppress it
    // until the desired target changes.
    private fun verifyPendingSwitch(frame: RuntimeFrame) {
        try {
            val pending = pendingTargetId ?: return
            val cursor = frame.cursorId
            if (cursor == pending) {
                RuntimeLogger.log(
                    "AUTOENGAGE switch took cursor=$cursor target=$pending",
                    "AUTOENGAGE"
                )
                pendingTargetId = null
                attempts = 0
                attemptsTargetId = null
                return
            }
            if (frame.frameId < pendingSinceFrameId) {
                pendingTargetId = null
                return
            }
            if (frame.frameId - pendingSinceFrameId > VERIFY_WINDOW_FRAMES) {
                attempts++
                if (attempts >= MAX_SWITCH_ATTEMPTS) {
                    suppressedTargetId = pending
                    RuntimeLogger.log(
                        "AUTOENGAGE switch suppressed target=$pending " +
                            "after $attempts failed attempts",
                        "AUTOENGAGE"
                    )
                }
                pendingTargetId = null
            }
        } catch (_: Throwable) {
            pendingTargetId = null
        }
    }

    // Arm D: drive the mover (the cursor) to the cut, or to the ball
    // when already engaged. Cut from the MOVER's position — the
    // steered player and the geometry agree (the InstantIntercept
    // wrong-man defect, fixed by construction). Throttled to one
    // drive per processed frame; the caller already applied the
    // never-fight (stick) gate.
    private fun offerDrive(
        frame: RuntimeFrame,
        mover: com.assistant.runtime.RuntimeEntity,
        w: Float,
        h: Float
    ) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastDriveMs < DRIVE_MIN_INTERVAL_MS) return
            val pred = BallTrajectoryPredictor.current()
            val cut = if (pred.valid) {
                BallPathInterceptEngine.interceptPoint(
                    pred.currentX, pred.currentY,
                    pred.velocityX, pred.velocityY,
                    mover.x, mover.y
                )
            } else null
            val (tx, ty) = if (cut != null) {
                cut
            } else if (distSq(mover.x, mover.y, frame.ballX, frame.ballY) <= engageDistanceSq) {
                frame.ballX to frame.ballY
            } else {
                return
            }
            val jx = ControlMappingTrainer.joystickCenterX() * w
            val jy = ControlMappingTrainer.joystickCenterY() * h
            val dx = tx - mover.x
            val dy = ty - mover.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (!dist.isFinite() || dist <= 0f) return
            val ceiling = DispatchBounds.maxDragPx
                ?: (minOf(w, h) * 0.08f)
            val radius = dist.coerceIn(0f, ceiling)
            val ux = dx / dist
            val uy = dy / dist
            val offered = ContributionRegistry.offer(
                ExecutionRequest(
                    source = ExecutionSource.INTERCEPTION,
                    phase = ActionClass.DEFEND.ordinal,
                    actionClass = ActionClass.DEFEND,
                    engine = engineName,
                    startX = jx,
                    startY = jy,
                    endX = (jx + ux * radius).coerceIn(0f, w - 1f),
                    endY = (jy + uy * radius).coerceIn(0f, h - 1f),
                    duration = DRIVE_DURATION_MS
                )
            )
            if (offered) lastDriveMs = now
        } catch (_: Throwable) {
            // Fail-open: a failed drive offer never breaks the collect loop.
        }
    }

    private fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}
