package com.assistant.contributors

import com.assistant.BallOwnershipEngine
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.*

/*
 * PlayerSwitch (Phase 5, step 3) — the FIRST contributor to emit
 * ActionClass.SWITCH. The switch channel (step 2) exists and nothing
 * dispatches today; this engine is the press.
 *
 * WHEN (all from existing frame data, no invented signal):
 * - We defend (opponent has the ball) and the game cursor is known
 *   (frame.cursorId, the ring sensor via FrameAssembler).
 * - The desired engager is the teammate nearest the ball, keeper
 *   excluded. Switch only when the best defender CAN engage and the
 *   cursor CANNOT: dist(desired, ball) <= ENGAGE_DISTANCE and
 *   dist(cursor, ball) > ENGAGE_DISTANCE, where ENGAGE_DISTANCE is
 *   BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE (60f) — referenced,
 *   not restated. If both are inside, the cursor can engage (no
 *   switch); if neither is inside, nobody can engage yet (no switch).
 *   The margin is the same constant on both sides, so there is no
 *   invented threshold to tune: one ownership-distance decides.
 *
 * WHICH (the direction, not the player): the contribution carries
 * target = desired player's position, aimOrigin = the cursor
 * player's position, targetEntityId = desired id, aimEntityId =
 * cursor id. RuntimeDecisionLoop.chooseRequest renders that as a
 * swipe FROM the slot-3 centre TOWARD the target point — the flick
 * direction the game reads. The game picks the target player by its
 * own logic; the app only commands the switch direction. A zero
 * distance (cursor and target on the same pixel) is never emitted:
 * chooseRequest drops it, and this engine refuses it first.
 *
 * PRESS + VERIFY + BOUNDED RETRY (state lives on this object; the
 * registry calls update() before contribute() every trusted frame):
 * - After emitting, the engine watches frame.cursorId for
 *   VERIFY_WINDOW_FRAMES (6 frames = 300 ms at the 20 fps processed
 *   rate — CHOSEN, stated as such). Cursor == target → the switch
 *   took; attempts reset.
 * - No take inside the window → one failed attempt. After
 *   MAX_SWITCH_ATTEMPTS (3 — CHOSEN) failures for the same target,
 *   that target is suppressed until the desired target changes — the
 *   engine never spams the switch. A new situation (different
 *   desired target) resets the count.
 * - SWITCH_COOLDOWN_FRAMES (10 frames = 500 ms at 20 fps — CHOSEN)
 *   separates dispatches. While a switch is pending verification,
 *   no second switch is emitted.
 *
 * AUTHORITY 0.60f is CHOSEN (above Marking's 0.55f, so the switch
 * outranks driving the wrong man to his mark); durationHintMs 40L
 * follows the Marking precedent. confidence is frame.confidence.
 *
 * POLICY (hands-off): this engine is policy-agnostic — it emits
 * SWITCH contributions and the dispatch authority decides. Today
 * chooseRequest hardcodes source = ExecutionSource.SMART_ASSIST
 * (RuntimeDecisionLoop.kt:524) and PerSourceTouchPolicy makes
 * SMART_ASSIST always require touch, so a SWITCH cannot dispatch
 * while the user's hands are off the screen. That is NOT worked
 * around here: no touch-gate change in this piece (Phase 0 policy,
 * Ed's decision). The proposed amendment is in the build report.
 * Until it is approved, switches dispatch only while the user is
 * touching — the engine, the channel and the cursor are all built,
 * and the hands-off half waits on that one decision.
 *
 * FAIL-OPEN: no cursorId → no request; no target → no request;
 * any fault → no request (whole body try/catch). The engine never
 * emits a switch it cannot aim. fastGate is deliberately permissive
 * (trusted + entities present) so update() runs every trusted frame
 * and the verify bookkeeping cannot stall; all trigger gating lives
 * in contribute().
 */
object PlayerSwitchContributor : GameplayContributor {
    override val engineName = "PlayerSwitch"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    // CHOSEN, stated as such (no repo constant exists for a switch
    // cadence): frames are the 20 fps processed rate (AutoEvadeEngine
    // :134/:148, AggressiveMemoryHoarding :174 "50ms gate, 20fps").
    private const val SWITCH_COOLDOWN_FRAMES = 10L // 500 ms between dispatches
    private const val VERIFY_WINDOW_FRAMES = 6L // 300 ms to see the cursor move
    private const val MAX_SWITCH_ATTEMPTS = 3 // per target, then suppress
    private const val AUTHORITY = 0.60f // CHOSEN: above Marking 0.55f
    private const val DURATION_HINT_MS = 40L // Marking precedent

    // Engagement range, referenced from the repo's own possession rule.
    private val engageDistanceSq: Float
        get() = BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE *
            BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE

    // Pending-switch bookkeeping. @Volatile: collect() runs on the
    // capture thread; nothing else writes these.
    @Volatile private var pendingTargetId: Int? = null
    @Volatile private var pendingSinceFrameId: Long = -1L
    @Volatile private var attemptsTargetId: Int? = null
    @Volatile private var attempts: Int = 0
    @Volatile private var suppressedTargetId: Int? = null
    @Volatile private var lastDispatchFrameId: Long = -1L

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.entities.isNotEmpty()

    override fun update(frame: RuntimeFrame) {
        try {
            val pending = pendingTargetId ?: return
            val cursor = frame.cursorId
            if (cursor == pending) {
                // The switch took: the ring now sits on our target.
                RuntimeLogger.log(
                    "PLAYERSWITCH took cursor=$cursor target=$pending",
                    "PLAYERSWITCH"
                )
                pendingTargetId = null
                attempts = 0
                attemptsTargetId = null
                return
            }
            if (frame.frameId < pendingSinceFrameId) {
                // Frame ids regressed (session reset) — drop the pending
                // switch without counting it as a failure.
                pendingTargetId = null
                return
            }
            if (frame.frameId - pendingSinceFrameId > VERIFY_WINDOW_FRAMES) {
                attempts++
                if (attempts >= MAX_SWITCH_ATTEMPTS) {
                    suppressedTargetId = pending
                    RuntimeLogger.log(
                        "PLAYERSWITCH suppressed target=$pending " +
                            "after $attempts failed attempts",
                        "PLAYERSWITCH"
                    )
                }
                pendingTargetId = null
            }
        } catch (_: Throwable) {
            // Fail-open: bookkeeping must never break the collect loop.
            pendingTargetId = null
        }
    }

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        try {
            if (!frame.trusted || frame.hasBall) return null
            val cursorId = frame.cursorId ?: return null
            val cursor = frame.entities.firstOrNull { it.id == cursorId && it.teammate }
                ?: return null
            // Desired engager: the teammate nearest the ball, keeper
            // excluded (the keeper never carries the cursor flag, and
            // switching TO the keeper is not a defensive switch).
            val desired = frame.entities
                .filter { it.teammate && !it.isGoalkeeper }
                .minByOrNull { distSq(it.x, it.y, frame.ballX, frame.ballY) }
                ?: return null
            if (desired.id == cursor.id) return null
            val engageSq = engageDistanceSq
            val nearSq = distSq(desired.x, desired.y, frame.ballX, frame.ballY)
            val cursorSq = distSq(cursor.x, cursor.y, frame.ballX, frame.ballY)
            if (nearSq > engageSq) return null // Best defender cannot engage yet.
            if (cursorSq <= engageSq) return null // Cursor can engage — no switch.
            if (desired.id == suppressedTargetId) return null // 3 failures: silent.
            if (pendingTargetId != null) return null // One switch at a time.
            if (lastDispatchFrameId >= 0L && frame.frameId >= lastDispatchFrameId &&
                frame.frameId - lastDispatchFrameId < SWITCH_COOLDOWN_FRAMES
            ) return null
            if (cursor.x == desired.x && cursor.y == desired.y) return null
            // A new target resets the failure count for the old one.
            if (attemptsTargetId != desired.id) {
                attempts = 0
                attemptsTargetId = desired.id
            }
            pendingTargetId = desired.id
            pendingSinceFrameId = frame.frameId
            lastDispatchFrameId = frame.frameId
            RuntimeLogger.log(
                "PLAYERSWITCH cursor=$cursorId -> target=${desired.id} " +
                    "attempt=${attempts + 1}",
                "PLAYERSWITCH"
            )
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.SWITCH,
                targetX = desired.x.coerceIn(0f, frame.captureWidth),
                targetY = desired.y.coerceIn(0f, frame.captureHeight),
                authority = AUTHORITY,
                confidence = frame.confidence,
                durationHintMs = DURATION_HINT_MS,
                controlSlot = 3,
                aimOriginX = cursor.x,
                aimOriginY = cursor.y,
                aimEntityId = cursor.id,
                targetEntityId = desired.id
            )
        } catch (_: Throwable) {
            return null
        }
    }

    private fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}
