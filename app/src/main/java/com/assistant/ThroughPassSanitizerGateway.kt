package com.assistant

import com.assistant.runtime.RuntimeFrame

/**
 * D13 — Through-Pass Sanitizer Gateway (engine-ideas §5 D13, Q68-1).
 *
 * Product decision (Ed, decided): through passes stay USER-ONLY — the
 * app never initiates one, to avoid ghost touch — but the sanitizer is
 * WIRED so Ed's through passes are clean and accurate.
 *
 * TRACE: CALLER RuntimeDecisionLoop.onFrame (per assembled frame) →
 * ENGINE this gateway (press-candidate + lane + possession gates) →
 * CALLEE ThroughPassSanitizer.sanitizeAndInjectThroughPass → CALLEE
 * LatencyDefeatingInputEngine.injectZeroLatencySwipe → CentralExecutionBus
 * (bus-routed, never a raw bypass) → DATA FLOW the frame's through
 * fields (receiver + direction-aware lead, B9) + the trainer's press
 * candidate → DECISION sanitize only on a FRESH physical through-press
 * candidate with a viable through lane while we have the ball, once
 * per candidate, 500 ms cooldown → MUTATION one bus-routed sanitized
 * swipe (pass button → dodged joystick target) → CONSUMER the game →
 * LIVE EFFECT Ed's through pass leaves along the dodged angle instead
 * of straight at the nearest defender.
 *
 * WHAT REMAINS BLOCKED (honest): the press itself is a CANDIDATE, not
 * a confirmed hardware press. The trainer records it from the slot-1
 * pixel leaving its at-rest green while the user is touching
 * (ControlMappingTrainer press-state recording) — the finger occludes
 * the button — plus the accessibility touch-interaction signal
 * (UserTouchState). That is an inference, and it is logged as
 * THROUGH_PRESS_CANDIDATE so Ed's log can confirm or refute it. True
 * observability needs input plumbing this codebase does not have: the
 * accessibility service receives touch-interaction START/END but no
 * touch coordinates, so the app cannot see WHICH button a finger is
 * on; and the game exposes no input hook. Until such plumbing exists
 * (a system-level touch-coordinate source or a game input hook — both
 * outside this repo), the gateway fires only on the candidate signal
 * above, and every fire is logged THROUGH_SANITIZED with its angles.
 * The gateway never fires without a candidate: no candidate, no
 * swipe — the app still never initiates a through pass on its own.
 */
object ThroughPassSanitizerGateway {

    /** A press candidate older than this is not Ed's current press. */
    const val CANDIDATE_FRESH_MS = 250L

    /** At most one sanitized swipe per press, and never more often than this. */
    const val SANITIZE_COOLDOWN_MS = 500L

    @Volatile private var inputEngine: LatencyDefeatingInputEngine? = null
    @Volatile private var lastSanitizedCandidateMs = 0L
    @Volatile private var lastSanitizeMs = 0L
    @Volatile private var lastUnboundLogMs = 0L

    /** Bound once by ActiveGestureController (it owns the input engine). */
    fun bind(engine: LatencyDefeatingInputEngine) {
        inputEngine = engine
    }

    /** Test hook: clears the binding + cooldown state. No production caller uses it. */
    fun resetForTest() {
        inputEngine = null
        lastSanitizedCandidateMs = 0L
        lastSanitizeMs = 0L
        lastUnboundLogMs = 0L
    }

    fun isBound(): Boolean = inputEngine != null

    /**
     * Sanitizes Ed's through press when the frame carries a fresh press
     * candidate + a viable through lane. Returns true only when a
     * sanitized swipe was accepted by the bus.
     */
    fun maybeSanitize(frame: RuntimeFrame, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!frame.trusted) return false
        if (!frame.throughPressed || !frame.throughLaneViable || !frame.hasBall) return false
        val candidateMs = ControlMappingTrainer.lastThroughPressMs()
        if (candidateMs <= 0L || candidateMs == lastSanitizedCandidateMs) return false
        if (nowMs - candidateMs > CANDIDATE_FRESH_MS) return false
        if (nowMs - lastSanitizeMs < SANITIZE_COOLDOWN_MS) return false
        val engine = inputEngine
        if (engine == null) {
            if (nowMs - lastUnboundLogMs >= 60_000L) {
                lastUnboundLogMs = nowMs
                try {
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "THROUGH_SANITIZER_UNBOUND (press candidate seen but no input engine bound; ActiveGestureController binds it at creation)",
                        "THROUGH"
                    )
                } catch (_: Throwable) { }
            }
            return false
        }
        val width = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val height = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        if (width <= 0f || height <= 0f) return false
        val passButton = ControlMappingTrainer.slotCenter(1)
        val passButtonX = passButton.first * width
        val passButtonY = passButton.second * height
        val joystickX = ControlMappingTrainer.joystickCenterX() * width
        val joystickY = ControlMappingTrainer.joystickCenterY() * height
        // Nearest defender to the RECEIVER is the dodge reference — the
        // sanitizer bends the pass away from the defender standing on
        // the receiver, not from the defender nearest the ball.
        val defender = frame.entities.filter { !it.teammate && it.x.isFinite() && it.y.isFinite() }
            .minByOrNull { (it.x - frame.throughReceiverX) * (it.x - frame.throughReceiverX) +
                (it.y - frame.throughReceiverY) * (it.y - frame.throughReceiverY) }
            ?: return false
        val sanitizer = ThroughPassSanitizer(engine)
        val ok = try {
            sanitizer.sanitizeAndInjectThroughPass(
                passButtonX = passButtonX,
                passButtonY = passButtonY,
                joystickX = joystickX,
                joystickY = joystickY,
                receiverX = frame.throughReceiverX,
                receiverY = frame.throughReceiverY,
                nearestDefenderX = defender.x,
                nearestDefenderY = defender.y
            )
        } catch (_: Throwable) { false }
        if (ok) {
            lastSanitizedCandidateMs = candidateMs
            lastSanitizeMs = nowMs
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "THROUGH_SANITIZED receiver=(${frame.throughReceiverX},${frame.throughReceiverY}) defender=(${defender.x},${defender.y}) lead=(${frame.throughLeadX},${frame.throughLeadY}) (user through press, sanitized)",
                    "THROUGH"
                )
            } catch (_: Throwable) { }
        }
        return ok
    }
}
