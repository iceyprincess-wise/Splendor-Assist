package com.assistant.contributors

import com.assistant.overlay.interceptor.PANIC_PRIORITY_THRESHOLD
import com.assistant.runtime.*

/*
 * Recovery / turn / push contributor (defender-side only).
 *
 * Ed's ask was a "recovery/turning/posture/push engine". The trace
 * (cd89596e) proved the premise half wrong, and this file is scoped to
 * what is genuinely ABSENT — do NOT extend it into keeper recovery:
 * keeper recovery already exists and is wired end-to-end (Item 12 A9:
 * OmnipotentGoalkeeperEngine + RecoveryPredictionEngine /
 * RecoveryVectorEngine / RecoveryPositionEngine, 7 call sites).
 * Defender recovery to the line after a drag-out also exists
 * (DefenseContributor drag-out branch), midfield recovery exists
 * (MidfieldHoldContributor), stance geometry exists
 * (DefensiveStanceProducer + VisionCore). What was absent:
 * TURNING (no turn-to-face logic anywhere), PUSH (zero identifiers,
 * zero engines), and the LONG-BALL -> DEFENSIVE-LINE link Ed ordered
 * (the long-ball signal never reached any defender — the keeper
 * rushed while the controlled defender kept his normal stopper/chase
 * behaviour). This contributor is that link, plus the defender
 * return / step-out the drag-out and low-block branches miss.
 *
 * TURNING folds INTO the movement vectors — the player turns as he
 * moves toward his slot. There is NO separate turn branch: a
 * "turn in place" gesture has no proven input in eFootball, and no
 * facing measurement exists to close a loop on
 * (TrackedPlayer.headingRadians is velocity-derived; a stationary
 * player has a stale/meaningless heading). If Ed wants an explicit
 * turn, that is his call, not this contributor's to invent.
 *
 * Constraint (MidfieldHoldContributor header, PROVEN): the app drives
 * ONE controlled player and there is no second input stream. "Push
 * out as a unit" cannot move the whole line — this steers the
 * controlled man to his stance slot; the rest of the line is game AI.
 *
 * Behaviours, in priority order (first match wins):
 *  1. LONG-BALL HOLD (posture): longBall && !hasBall && a stance is
 *     known -> hold the controlled defender at his stance slot
 *     (homeX/homeY, or dragOutGapX when the line is stretched). Must
 *     NOT chase the ball. This is the missing "hand in hand" link:
 *     the keeper rushes the long ball while the defender holds.
 *  2. RECOVER (defender return): !hasBall && no panic-level live
 *     threat && the controlled defender sits goal-side of the line
 *     by more than a full line spread -> drive him to homeX/homeY
 *     (his own lane). Covers the post-press/post-rush gap the
 *     drag-out branch (line stretched past 25% only) and the low
 *     block (ball deep + goal known only) miss. The margin IS the
 *     line spread itself — a measured quantity, not an invented
 *     pixel constant. Gated off while dragOutHeld: the drag-out
 *     branch owns line reform when the line is stretched.
 *  3. PUSH (step out): !hasBall && no panic-level live threat &&
 *     the defender sits just behind his stance slot (within one
 *     spread) -> drive him forward to the line after the threat
 *     drops (clearance / keeper collects). The achievable form of
 *     "press forward as a unit" with one input stream. Also gated
 *     off while dragOutHeld.
 *
 * "Goal-side" uses the repo's own axis rule (DefensiveLineEngine
 * comment): attack right (attackDirection >= 0, including
 * unconfident 0) puts our goal at the left edge (smallest X
 * deepest); attack left puts it at the right edge (largest X
 * deepest). "No panic-level live threat" is the keeper decision's
 * priority against PANIC_PRIORITY_THRESHOLD (150) — the repo's
 * shared panic line, not an invented number. A mid-level threat
 * does not block a return: walking back to his slot during a
 * developing threat is defensively sound; only a panic-level
 * threat pins the defender where he is.
 *
 * Authority (stated tuning, needs live-play calibration):
 *  - HOLD = defenderDensity + 0.10f, capped at 1. It beats the
 *    stopper chase during a long ball — that IS the hold. It
 *    outranks the drag-out branch only while dragOutHeld, where
 *    both target the same gap slot, so the drag-out OUTCOME (the
 *    gap slot wins) is preserved; the drag-out engine name is not
 *    what matters, the slot is. Beats the press (defenderDensity
 *    x pressScale <= defenderDensity).
 *  - RECOVER = 0.65f FIXED. Beats MidfieldHold's 0.60f, so a
 *    defender behind his own line returns to it instead of being
 *    held at midfield; wins over the normal stopper only when
 *    defenderDensity < 0.65 — i.e. exactly when the defender is
 *    isolated behind play. At higher density the collective
 *    stopper/press wins and he joins it.
 *  - PUSH = 0.55f FIXED (the Marking rung: MidfieldHold 0.60 was
 *    set to beat Marking's 0.55). Below MidfieldHold: stepping out
 *    after the threat drops is the lowest priority of the three
 *    and must not outrank midfield shape.
 *
 * Class is DEFEND, not a movement-class offer: the arbitration
 * trace (ConAIEngine) proves a movement-class offer while
 * defending wins only when no DEFEND/KEEPER offer has authority
 * >= 0.25 and is then scaled 0.35 — a hold that must move the
 * defender while defending has to be DEFEND. confidence is the
 * frame confidence; durationHintMs is 40L (Marking precedent).
 */
object RecoveryTurnPushContributor : GameplayContributor {
    override val engineName = "RecoveryTurnPush"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall &&
            (frame.homeFound || frame.defensiveLineSpread > 0f || frame.dragOutHeld)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return null
        val controlled = frame.entities.firstOrNull { it.id == frame.defenderId && it.teammate }
            ?: frame.entities.filter { it.teammate }.minByOrNull { e ->
                (e.x - frame.ballX) * (e.x - frame.ballX) +
                    (e.y - frame.ballY) * (e.y - frame.ballY)
            } ?: return null
        val screenW = frame.captureWidth
        val screenH = frame.captureHeight

        // 1. LONG-BALL HOLD (posture): hold the stance slot, never chase.
        if (frame.longBall && (frame.homeFound || frame.dragOutHeld)) {
            val slotX = if (frame.dragOutHeld && frame.dragOutGapX.isFinite()) frame.dragOutGapX
                else frame.homeX
            val slotY = if (frame.homeFound) frame.homeY else controlled.y
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.DEFEND,
                targetX = slotX.coerceIn(0f, screenW),
                targetY = slotY.coerceIn(0f, screenH),
                authority = (frame.defenderDensity + 0.10f).coerceIn(0f, 1f),
                confidence = frame.confidence,
                durationHintMs = 40L,
                targetEntityId = controlled.id
            )
        }

        // No panic-level live threat from here on; drag-out owns line
        // reform while the line is stretched.
        if (frame.threatPriority >= PANIC_PRIORITY_THRESHOLD) return null
        if (frame.dragOutHeld) return null

        // Goal-side distance of the controlled defender from the line:
        // positive = goal-side of the line (see header for the axis rule).
        val goalSideGap = if (frame.attackDirection < 0) {
            controlled.x - frame.defensiveLineX
        } else {
            frame.defensiveLineX - controlled.x
        }
        val spread = frame.defensiveLineSpread

        // 2. RECOVER: far behind the line (more than a full spread) —
        // drive back to his own home on the line.
        if (frame.homeFound && spread > 0f && goalSideGap > spread) {
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.DEFEND,
                targetX = frame.homeX.coerceIn(0f, screenW),
                targetY = frame.homeY.coerceIn(0f, screenH),
                authority = 0.65f,
                confidence = frame.confidence,
                durationHintMs = 40L,
                targetEntityId = controlled.id
            )
        }

        // 3. PUSH: just behind his slot (within one spread) — step out
        // to the line after the threat drops. Slot is his home when
        // known, else the line average. With no measured spread, any
        // behind-slot gap qualifies (RECOVER above already required a
        // measured spread, so the two cannot both fire).
        val slotX = if (frame.homeFound) frame.homeX else frame.defensiveLineX
        val slotY = if (frame.homeFound) frame.homeY else controlled.y
        val goalSideSlotGap = if (frame.attackDirection < 0) {
            controlled.x - slotX
        } else {
            slotX - controlled.x
        }
        if (goalSideSlotGap > 0f && (spread <= 0f || goalSideSlotGap <= spread)) {
            return EngineContribution(
                engine = engineName,
                actionClass = ActionClass.DEFEND,
                targetX = slotX.coerceIn(0f, screenW),
                targetY = slotY.coerceIn(0f, screenH),
                authority = 0.55f,
                confidence = frame.confidence,
                durationHintMs = 40L,
                targetEntityId = controlled.id
            )
        }
        return null
    }
}
