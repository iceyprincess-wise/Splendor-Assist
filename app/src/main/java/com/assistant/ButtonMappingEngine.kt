package com.assistant

/**
 * D7 — Button Mapping Engine (batch 6a).
 *
 * The earlier screenshot-based button-mapping engine from Ed's
 * button-config screenshot was VERIFIED ABSENT from the repo (batch 1); this engine is built fresh
 * from Ed's measured screenshots instead. It is the single source of
 * measured button geometry for the app.
 *
 * MEASURED GROUND TRUTH (parent-measured 2026-11-19 from Ed's 13
 * screenshots, PLAN.md D7 section — do NOT re-measure, do NOT guess):
 * every control agrees within ~2 px between the attacking and defending
 * layouts, so ONE geometry serves both sides — only the labels change.
 * Centres below are NORMALIZED on the source frame (1600x720).
 *
 *   Joystick            (0.1806, 0.7208)  radius ~58 source px
 *   Tackle / Shoot      (0.9150, 0.5472)  radius ~68 source px
 *   Match-up / Through  (0.8025, 0.6153)  radius ~68 source px
 *   Dash (LARGE)        (0.9044, 0.8292)  radius ~79 source px
 *   Switch / Pass       (0.7825, 0.8764)  radius ~68 source px
 *
 * SPACES: the source frame is landscape 1600x720. The in-play capture is
 * landscape 640x288 (x0.4 — same aspect, so the normalized centres above
 * apply unchanged). A portrait capture is 288x640 with the landscape
 * content rotated: CW (288-y, x) or CCW (y, 640-x) in landscape-capture
 * px, i.e. normalized CW (1-ny, nx) and CCW (ny, 1-nx). Which portrait
 * rotation is live is NOT known in-repo — nothing records it — so the
 * engine takes the orientation as an input and never guesses it.
 *
 * ANCHOR CROSS-CHECK (CLOSED — RC1 fix 2026-11-24): the app's trained
 * joystick anchor (0.182w, 0.648h) matches the measured joystick x
 * exactly (0.1806 vs 0.182) but its y sits on the joystick's TOP EDGE:
 * anchor y = 466.6 source px vs measured centre 519 — 52 source px /
 * 21 landscape-capture px low. Dispatching from the anchor made
 * down-field movement ~9% of intended deflection (one full radius of
 * bias). The live dispatch now defaults to the MEASURED centre in
 * landscape (see resolvedJoystickCenter); the Q-N6-1 probe hook below
 * (probeCandidates / recordProbeOutcome / resolvedJoystickCenter)
 * remains as the confirmation/revert path — an explicit ANCHOR verdict
 * still restores the anchor, and the probe runs after the re-anchor
 * as confirmation on Ed's device.
 *
 * TRAINER NOTE: ControlMappingTrainer.SLOTS are the same buttons with
 * stale centres (slots 3/4 probe OUTSIDE their measured discs in
 * landscape capture, so Pass/Dash could never train; slots 1/2 probe
 * inside but off-centre). The trainer resolves slot centres through
 * this engine when the live capture is landscape. The trainer's
 * DEFEND labels for slots 1 and 3 are also swapped vs the measured
 * layout (slot 1 says Switch, measured Match-up; slot 3 says
 * Match-up, measured Switch) — the engine carries the correct labels
 * here; the trainer keeps its legacy label strings until the
 * rotation/probe work lands (labels only feed move-classification
 * strings, not dispatch).
 *
 * TRACE: CALLER ControlMappingTrainer (slot probes / press candidates /
 * slotCenter) + RuntimeDecisionLoop (via the trainer) → ENGINE this
 * geometry + probe state → CALLEE none (pure math) → DATA FLOW measured
 * centres + live capture orientation → DECISION centre per button per
 * orientation; joystick stays anchored until the probe decides →
 * MUTATION slot probe points + (after the probe) the joystick point →
 * CONSUMER the trainer's UiMode / slot-training / through-press /
 * dash-hold state and the dispatch joystick origin → LIVE EFFECT slot
 * training can succeed on the true buttons (Pass/Dash unblocked) and
 * UiMode detects IN_MATCH from real button pixels. UNVERIFIED
 * on-device until Ed's session log shows UiMode=IN_MATCH with the new
 * centres and the Q-N6-1 probe verdict.
 *
 * Pure Kotlin, no Android dependency — directly unit-testable.
 */
object ButtonMappingEngine {

    enum class Button {
        JOYSTICK,
        TACKLE_SHOOT,
        MATCHUP_THROUGH,
        DASH,
        SWITCH_PASS
    }

    /** Capture orientation the centres are expressed in. */
    enum class Orientation {
        LANDSCAPE,
        PORTRAIT_CW,
        PORTRAIT_CCW
    }

    /** Measured centre, normalized on the source frame (1600x720). */
    data class MeasuredButton(
        val button: Button,
        val nx: Float,
        val ny: Float,
        /** Radius in source px (~68 small, ~79 Dash, ~58 joystick). */
        val radiusSourcePx: Float,
        val attackLabel: String,
        val defendLabel: String
    )

    val BUTTONS: List<MeasuredButton> = listOf(
        MeasuredButton(Button.JOYSTICK, 0.1806f, 0.7208f, 58f, "Joystick", "Joystick"),
        MeasuredButton(Button.TACKLE_SHOOT, 0.9150f, 0.5472f, 68f, "Shoot (Clear)", "Tackle"),
        MeasuredButton(Button.MATCHUP_THROUGH, 0.8025f, 0.6153f, 68f, "Through", "Match-up"),
        MeasuredButton(Button.DASH, 0.9044f, 0.8292f, 79f, "Dash", "Dash & Pressure"),
        MeasuredButton(Button.SWITCH_PASS, 0.7825f, 0.8764f, 68f, "Pass", "Switch")
    )

    /** The app's trained joystick anchor (ControlMappingTrainer JOY_NX/Y). */
    const val TRAINED_JOY_NX = 0.182f
    const val TRAINED_JOY_NY = 0.648f

    fun measured(button: Button): MeasuredButton =
        BUTTONS.first { it.button == button }

    /**
     * Centre of [button] in capture-normalized coords for [orientation].
     * LANDSCAPE reuses the source-normalized values (same aspect).
     * Portrait applies the rotation: CW (1-ny, nx), CCW (ny, 1-nx).
     */
    fun center(button: Button, orientation: Orientation): Pair<Float, Float> {
        val m = measured(button)
        return when (orientation) {
            Orientation.LANDSCAPE -> Pair(m.nx, m.ny)
            Orientation.PORTRAIT_CW -> Pair(1f - m.ny, m.nx)
            Orientation.PORTRAIT_CCW -> Pair(m.ny, 1f - m.nx)
        }
    }

    // ---- Q-N6-1 joystick probe hook ----

    enum class JoystickProbeOutcome { UNDECIDED, ANCHOR, MEASURED }

    @Volatile
    private var probeOutcome: JoystickProbeOutcome = JoystickProbeOutcome.UNDECIDED

    /** Test hook / probe reset: clears a recorded verdict. No production caller uses it. */
    fun resetProbe() {
        probeOutcome = JoystickProbeOutcome.UNDECIDED
    }

    fun probeOutcome(): JoystickProbeOutcome = probeOutcome

    /**
     * The trained anchor expressed in [orientation] space. The anchor is
     * a landscape-normalized point (PLAN.md cross-check: its source px
     * are (291, 466.6)); portrait applies the same rotation as [center].
     * The Q-N6-1 probe is a LANDSCAPE probe — portrait candidates are
     * supplied for completeness, not as a probe verdict.
     */
    fun anchorCenter(orientation: Orientation): Pair<Float, Float> = when (orientation) {
        Orientation.LANDSCAPE -> Pair(TRAINED_JOY_NX, TRAINED_JOY_NY)
        Orientation.PORTRAIT_CW -> Pair(1f - TRAINED_JOY_NY, TRAINED_JOY_NX)
        Orientation.PORTRAIT_CCW -> Pair(TRAINED_JOY_NY, 1f - TRAINED_JOY_NX)
    }

    /**
     * The two joystick candidates for [orientation], in capture-normalized
     * coords: first the trained ANCHOR, second the MEASURED centre. The
     * on-device probe (Q-N6-1) dispatches a test gesture at each and
     * records which y the game responds to via [recordProbeOutcome].
     * The probe execution itself needs Ed on the device — this engine
     * only supplies the candidates and records the verdict.
     */
    fun probeCandidates(orientation: Orientation): Pair<Pair<Float, Float>, Pair<Float, Float>> =
        Pair(anchorCenter(orientation), center(Button.JOYSTICK, orientation))

    /**
     * Records the Q-N6-1 verdict: [useMeasured] true when the game
     * responded to the measured centre, false when it responded to the
     * anchor (or to neither — the anchor stays live either way).
     */
    fun recordProbeOutcome(useMeasured: Boolean) {
        probeOutcome = if (useMeasured) JoystickProbeOutcome.MEASURED else JoystickProbeOutcome.ANCHOR
    }

    /**
     * The live joystick centre for [orientation].
     *
     * RC1 fix (2026-11-24): the trained anchor is PROVEN wrong in
     * landscape (52 source px above the measured centre, on the
     * joystick's top edge), so an UNDECIDED probe now defaults to the
     * MEASURED centre in landscape — dispatch from the true centre.
     * An explicit verdict still wins: ANCHOR restores the anchor
     * (the revert path), MEASURED keeps the measured centre.
     * Portrait keeps the anchor while UNDECIDED: the rotation is
     * unknown, so the measured centre cannot be trusted there.
     * No probe verdict is fabricated here — the default changed,
     * the verdict mechanism is untouched.
     */
    fun resolvedJoystickCenter(orientation: Orientation): Pair<Float, Float> =
        when (probeOutcome) {
            JoystickProbeOutcome.ANCHOR -> anchorCenter(orientation)
            JoystickProbeOutcome.MEASURED -> center(Button.JOYSTICK, orientation)
            JoystickProbeOutcome.UNDECIDED ->
                if (orientation == Orientation.LANDSCAPE) center(Button.JOYSTICK, orientation)
                else anchorCenter(orientation)
        }
}
