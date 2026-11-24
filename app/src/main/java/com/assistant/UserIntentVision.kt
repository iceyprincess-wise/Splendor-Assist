package com.assistant

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * UserIntentVision (Tier 2) — per-slot user-intent inference from
 * on-screen control pixels.
 *
 * GAP (verified at 7bf1c686): UserTouchState is a single global
 * AtomicBoolean — it says THAT the user touches, never WHICH control
 * they hold. The trainer already infers two holds this way (slot 1
 * Through, slot 4 Dash: centre pixel leaving its at-rest green while
 * touching, 2 consecutive frames = a press/hold CANDIDATE). Slots 2
 * (Shoot/Tackle) and 3 (Pass/Switch) had no hold probe, and the
 * joystick had none — so the assist could not tell "user holds Shoot"
 * from "user holds the joystick" from "user holds nothing".
 *
 * SIGNAL (same proven pattern, extended): a control is HELD when its
 * centre pixel leaves its at-rest colour while the user is touching,
 * for 2 consecutive in-match frames. Buttons are green at rest
 * (trainer's isGreen); the joystick disc is grey-or-green at rest
 * (trainer's measureMaxDrag test). A hold is a CANDIDATE — an
 * inference, never a confirmed hardware press — fresh for 250 ms,
 * exactly like the Through/Dash candidates.
 *
 * SLOTS: 0 = joystick, 1 = Through, 2 = Shoot, 3 = Pass, 4 = Dash
 * (the trainer's slot ids; RDL requiredControlSlot's map). The mask
 * is bit 0 = joystick, bits 1-4 = slots 1-4. Slots 1 and 4 reuse the
 * trainer's existing candidates (no duplicate state); slots 2, 3 and
 * the joystick are probed here.
 *
 * GEOMETRY: no hardcoded coordinates. Slot centres resolve through
 * ControlMappingTrainer.slotCenter() (D7 ButtonMappingEngine in
 * landscape, trained arrays in portrait). The joystick READ point is
 * the ButtonMappingEngine MEASURED centre in landscape — the true disc
 * centre — while DISPATCH keeps the trainer anchor until the Q-N6-1
 * probe decides (this engine never re-anchors dispatch; it only
 * chooses where to look).
 *
 * KNOB DEFLECTION: deliberately NOT a live reading. While the user
 * holds the joystick their finger occludes the knob, so the knob's
 * position is not pixel-readable from the frame; and no knob colour
 * constant exists in-repo to segment it from the base. The engine
 * reports HELD vs NOT-HELD for the joystick, never a deflection
 * direction — a direction-aware gate needs the knob deflection, which
 * needs a measured knob colour (Ed's screenshots) first.
 *
 * FAIL-OPEN: a failed pixel read (out of bounds / short buffer) is
 * "unknown", never "held" — the occlusion counter resets. Mask 0
 * means "no hold detected" and suppresses nothing downstream.
 * (Deliberately stricter than the trainer's D13 pattern, which treats
 * a failed probe as not-green; this engine must never hallucinate a
 * hold on a misread.)
 *
 * CALLER: ControlMappingTrainer.observe()'s IN_MATCH branch, on the
 * same capture buffer, right after recordDashHoldState. Pixels are
 * read via ControlMappingTrainer.probePixel (the trainer's own probe
 * path, so probesRead telemetry stays accurate).
 * CONSUMER: FrameAssembler copies heldSlotMask() onto
 * RuntimeFrame.userIntentMask; RuntimeDecisionLoop's never-fight-the-
 * user gate holds a request whose slot is in the mask.
 */
object UserIntentVision {

    /** Slot ids: 0 = joystick, 1..4 = trainer button slots. */
    const val SLOT_JOYSTICK = 0
    const val SLOT_THROUGH = 1
    const val SLOT_SHOOT = 2
    const val SLOT_PASS = 3
    const val SLOT_DASH = 4

    /** Bit for a slot id in the mask. */
    fun slotBit(slot: Int): Int = 1 shl slot

    private const val OCCLUDED_FRAMES_TO_CONFIRM = 2
    private const val CANDIDATE_FRESH_MS = 250L
    private const val LOG_MIN_INTERVAL_MS = 60_000L

    private class HoldState(val label: String) {
        var occludedFrames: Int = 0
        @Volatile var active: Boolean = false
        @Volatile var lastMs: Long = 0L
        var lastLogMs: Long = 0L
    }

    private val shootHold = HoldState("SHOOT")
    private val passHold = HoldState("PASS")
    private val joystickHold = HoldState("JOYSTICK")

    private fun isGreenRgb(rgb: IntArray) = rgb[1] > rgb[0] + 8 && rgb[1] > rgb[2] + 8
    private fun isGrayRgb(rgb: IntArray) =
        abs(rgb[0] - rgb[1]) <= 14 && abs(rgb[1] - rgb[2]) <= 14 && rgb[0] in 110..215
    private fun isJoystickRgb(rgb: IntArray) = isGreenRgb(rgb) || isGrayRgb(rgb)

    /**
     * The joystick READ point: the measured disc centre in landscape
     * (ButtonMappingEngine — the true centre; the dispatch anchor sits
     * 52 source px low on the top edge, so reading there would straddle
     * the disc boundary). Portrait falls back to the trainer anchor —
     * the portrait rotation is not recorded anywhere, so the engine
     * never guesses a rotated measured centre.
     */
    private fun joystickReadCenter(w: Int, h: Int): Pair<Float, Float> =
        if (w > h) ButtonMappingEngine.center(
            ButtonMappingEngine.Button.JOYSTICK,
            ButtonMappingEngine.Orientation.LANDSCAPE
        ) else Pair(
            ControlMappingTrainer.joystickCenterX(),
            ControlMappingTrainer.joystickCenterY()
        )

    /** One hold update: 2 consecutive occluded frames confirm, 250 ms freshness, 60 s log throttle. */
    private fun updateHold(state: HoldState, occluded: Boolean, now: Long) {
        if (occluded) state.occludedFrames++ else state.occludedFrames = 0
        if (state.occludedFrames >= OCCLUDED_FRAMES_TO_CONFIRM) {
            state.active = true
            state.lastMs = now
            if (now - state.lastLogMs >= LOG_MIN_INTERVAL_MS) {
                state.lastLogMs = now
                try {
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "USER_INTENT_HOLD slot=${state.label} occludedFrames=${state.occludedFrames} (inferred: finger occludes the control while touching; NOT a confirmed hardware press)",
                        "USERINTENT"
                    )
                } catch (_: Throwable) { }
            }
        } else if (now - state.lastMs > CANDIDATE_FRESH_MS) {
            state.active = false
        }
    }

    private fun HoldState.fresh(now: Long): Boolean = active && now - lastMs <= CANDIDATE_FRESH_MS

    /** Per-frame pixel observation. Called only from the trainer's IN_MATCH branch. */
    fun observeFrame(b: ByteBuffer, w: Int, h: Int, stride: Int, pixelStride: Int) {
        if (w <= 0 || h <= 0) return
        val now = System.currentTimeMillis()
        val touching = UserTouchState.isTouching()
        // Slots 2 (Shoot) and 3 (Pass): centre pixel leaving its
        // at-rest green while touching. A failed probe is "unknown",
        // never "held" — the counter resets (fail-open).
        for ((slot, state) in arrayOf(
            SLOT_SHOOT to shootHold,
            SLOT_PASS to passHold
        )) {
            val (cx, cy) = ControlMappingTrainer.slotCenter(slot)
            val rgb = ControlMappingTrainer.probePixel(b, w, h, stride, cx, cy, pixelStride)
            val occluded = rgb != null && !isGreenRgb(rgb) && touching
            updateHold(state, occluded, now)
        }
        // Joystick: measured-centre pixel leaving grey-or-green while
        // touching (finger occludes the knob/base). Same 2-frame rule.
        val (jx, jy) = joystickReadCenter(w, h)
        val jrgb = ControlMappingTrainer.probePixel(b, w, h, stride, jx, jy, pixelStride)
        val joyOccluded = jrgb != null && !isJoystickRgb(jrgb) && touching
        updateHold(joystickHold, joyOccluded, now)
    }

    /** True while a Shoot-hold candidate is fresh (within 250 ms). */
    fun shootHoldCandidateFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        shootHold.fresh(nowMs)

    /** True while a Pass-hold candidate is fresh (within 250 ms). */
    fun passHoldCandidateFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        passHold.fresh(nowMs)

    /** True while a joystick-hold candidate is fresh (within 250 ms). */
    fun joystickHoldCandidateFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        joystickHold.fresh(nowMs)

    /**
     * Bitmask of currently-held slots: bit 0 = joystick, bits 1-4 =
     * buttons 1-4. Slots 1 and 4 come from the trainer's existing
     * candidates; slots 2, 3 and the joystick from this engine. 0 =
     * no hold detected (fail-open).
     */
    fun heldSlotMask(nowMs: Long = System.currentTimeMillis()): Int {
        var mask = 0
        if (joystickHold.fresh(nowMs)) mask = mask or slotBit(SLOT_JOYSTICK)
        if (ControlMappingTrainer.throughPressCandidateFresh(nowMs)) mask = mask or slotBit(SLOT_THROUGH)
        if (shootHold.fresh(nowMs)) mask = mask or slotBit(SLOT_SHOOT)
        if (passHold.fresh(nowMs)) mask = mask or slotBit(SLOT_PASS)
        if (ControlMappingTrainer.dashHoldCandidateFresh(nowMs)) mask = mask or slotBit(SLOT_DASH)
        return mask
    }
}
