package com.assistant

import com.assistant.runtime.*

import java.util.Locale

import kotlin.math.*

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
     * CHASE ARM (#43): the dash knock-on recovery. When Ed sprints,
     * the ball is knocked ahead of the carrier and the trap window
     * goes LEAVING — the existing trap returns null there. The arm
     * chases the knocked ball down while a defender threatens it.
     * CHASE_MAX_DISTANCE (120 px) is a JUSTIFIED STARTING VALUE (2x
     * ownership), NOT a measurement — the M1-M4 log pass from Ed's
     * v272 logs will calibrate it. Below 18 px the ball is already
     * tight and the trap path owns it; beyond 120 px the ball is a
     * loose ball and the contest logic owns it.
     */
    private const val CHASE_MIN_DISTANCE = 18.0f
    private const val CHASE_MAX_DISTANCE = 120.0f
    private const val CHASE_PRESS_DISTANCE = 45.0f

    /*
     * No68-B-D18: there is no joystick origin in this file anymore.
     * The target is a VISION-SPACE point on the owner->ball ray (the
     * convention every other MOVE contributor uses): chooseRequest()
     * derives the joystick deflection as (target - aimOrigin), so a
     * joystick-space target here skewed the direction by
     * (joystickOrigin - owner) and could drive the carrier AWAY from
     * the ball. The aim origin is the owner itself (see contribute()).
     */

    private const val MIN_TRAVEL = 45.0f
    private const val MAX_TRAVEL = 180.0f

    private val SCREEN_MAX_X get() = com.assistant.vision.CameraProfile.captureWidthOrFallback()
    private val SCREEN_MAX_Y get() = com.assistant.vision.CameraProfile.captureHeightOrFallback()

    /*
     * Same live floor used by FrameAssembler for possession confidence.
     */
    private const val POSSESSION_MIN_CONFIDENCE = 0.20f

    // SPLENDOR_P10_TRAP_STATE: last trap window, for the window-enter
    // TRAP log (one line per CLOSING entry, not per frame).
    @Volatile
    private var prevTrapWindow = "none"

    // CHASE ARM: last chase-arm firing state, for the chase-enter
    // CHASE log (one line per chase entry, not per frame).
    @Volatile
    private var prevChaseActive = false

    // No68-B-D17: the base-class fastGate does not apply here -- this
    // contributor implements GameplayContributor directly, so the
    // default fastGate was `true` and the full contribute() body ran
    // on panic frames. Mirror the ObservedGameplayEngines base gate
    // (trusted, not panicking, ball in play); the registry already
    // checks trusted, but panic is only checked here.
    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.panic && (frame.hasBall || isChaseCandidate(frame))

    /*
     * CHASE ARM (#43): frame-only chase pre-check. A knock-on frame
     * has hasBall=false (possession lost past 60 px, and
     * FrameAssembler drops hasBall with it), so the plain hasBall gate
     * would never let the chase arm run. Open the door only when a
     * chaser exists: a user-team outfield entity within the chase band
     * of a finite ball. contribute() re-checks everything (dash,
     * LEAVING, PRESS, stick window) before emitting anything.
     */
    private fun isChaseCandidate(frame: RuntimeFrame): Boolean {
        if (!frame.ballX.isFinite() || !frame.ballY.isFinite()) return false
        return frame.entities.any { e ->
            e.teammate && !e.isGoalkeeper &&
                hypot(
                    (e.x - frame.ballX).toDouble(),
                    (e.y - frame.ballY).toDouble()
                ).toFloat() in CHASE_MIN_DISTANCE..CHASE_MAX_DISTANCE
        }
    }

    override fun initialize() {
        MagneticFeetEngine.reset()
    }

    override fun contribute(
        frame: RuntimeFrame
    ): EngineContribution? {

        /*
         * Use the same runtime truth gates as the rest of the gameplay
         * contributor architecture.
         */
        /*
         * CHASE ARM (#43): hasBall is NOT required at this gate. A dash
         * knock-on loses possession (BallPossessionEngine drops the
         * owner when the ball passes 60 px, and FrameAssembler drops
         * hasBall with it), so requiring hasBall here would make the
         * chase arm dead on arrival. The trap path below still
         * requires possession + hasBall; only the chase arm runs on
         * possession-lost frames.
         */
        if (
            !frame.enabled ||
            !frame.trusted
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

        /*
         * Possession-held frames take the existing trap path. On a
         * knock-on the ball passes 60 px, BallPossessionEngine returns
         * hasPossession=false with ownerIndex=-1, and the chaser is
         * re-derived below as the nearest user-team outfield player
         * to the ball. The possession-confidence gate is dropped for
         * the chase case ONLY: requiring it would make the arm dead on
         * arrival (possession is lost past 60 px by definition).
         */
        val possessionHeld =
            possession.hasPossession &&
                possession.confidence >=
                    POSSESSION_MIN_CONFIDENCE &&
                possession.ownerIndex >= 0

        val scene =
            try {
                SceneTracker.current()
            } catch (_: Throwable) {
                return null
            }

        val ballX =
            frame.ballX

        val ballY =
            frame.ballY

        if (
            !ballX.isFinite() ||
            !ballY.isFinite()
        ) {
            return null
        }

        /*
         * BallOwnershipEngine's ownerIndex points into the same
         * SceneTracker snapshot that produced the possession result.
         * When possession is lost the chaser is the nearest user-team
         * outfield player to the ball in that same snapshot.
         */
        val owner =
            if (possessionHeld) {
                scene.trackedPlayers.getOrNull(
                    possession.ownerIndex
                ) ?: return null
            } else {
                nearestChaser(scene.trackedPlayers, ballX, ballY) ?: return null
            }

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

        if (
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
         * SPLENDOR_P10_TRAP_GATE: first-touch gate. Ball velocity toward the
         * owner (closing speed) plus nearest-defender distance, in
         * px/frame and px throughout. frame.ballVelocityX/Y are per-frame
         * deltas (BallTelemetryBridge publishes x - previousX, no time
         * division), so they are NEVER multiplied by seconds here.
         * Nearest-defender distance is derived from frame.entities --
         * TelemetrySnapshot.opponentDistance is a goalkeeper pixel-scan
         * value, not a distance to the owner, and never reaches
         * RuntimeFrame. Bands reuse repo constants only: 5 px/frame
         * intercept floor (ObservedGameplayEngines), 18-38 px sweet spot
         * (SharpTouchTimingEngine), 15/45 px Dreadnought bands, 0..6
         * frame intercept horizon.
         */
        val trapUx = deltaX / distance
        val trapUy = deltaY / distance
        val closingSpeed = frame.ballVelocityX * trapUx + frame.ballVelocityY * trapUy
        val trapBallSpeed = hypot(frame.ballVelocityX.toDouble(), frame.ballVelocityY.toDouble()).toFloat()
        val nearestDefenderDist = frame.entities.filter { !it.teammate }
            .minOfOrNull { e -> hypot((e.x - owner.x).toDouble(), (e.y - owner.y).toDouble()).toFloat() }
            ?: Float.MAX_VALUE
        val trapMargin = 5f * (1f - frame.confidence.coerceIn(0f, 1f))
        // No68-B-D24: the band is computed once and the weight derived
        // from it -- the old code evaluated the same two thresholds in
        // two separate `when` blocks, which could drift apart.
        val trapBand = when {
            nearestDefenderDist <= 15f + trapMargin -> "HOT"
            nearestDefenderDist <= 45f + trapMargin -> "PRESS"
            else -> "LOOSE"
        }
        val trapBandWeight = when (trapBand) {
            "HOT" -> 1.0f
            "PRESS" -> 0.6f
            else -> 0.25f
        }
        val trapWindow = when {
            closingSpeed >= 5f -> "CLOSING"
            trapBallSpeed < 5f -> "SETTLED"
            else -> "LEAVING"
        }
        // No68-B-D21: (distance - 18f) goes negative once the ball is
        // already inside the 18 px sweet spot, so the most urgent balls
        // -- closing fast at close range -- scored a negative time and
        // missed the boost window entirely. Clamp at zero: already
        // inside the sweet spot means contact is now.
        val timeToContactF = if (closingSpeed > 0f) maxOf(0f, (distance - 18f) / closingSpeed) else Float.POSITIVE_INFINITY
        val trapBoost = if (closingSpeed >= 5f && timeToContactF in 0f..6f) 1.0f + 0.25f * trapBandWeight else 1.0f
        val prePosition = closingSpeed >= 5f && timeToContactF > 6f
        val enteredTrapWindow = trapWindow == "CLOSING" && prevTrapWindow != "CLOSING"
        prevTrapWindow = trapWindow
        MagneticFeetEngine.recordTrapWindow(trapWindow)
        // CHASE ARM (#43): the knock-on recovery. On a LEAVING frame
        // the ball is moving AWAY from the owner — the existing trap
        // returns null here, and the arm below is the only path that
        // may still fire (dash sprint knock-on). It returns the chase
        // MOVE or null; it never falls through to the trap path.
        if (trapWindow == "LEAVING") {
            return tryChaseArm(
                frame = frame,
                owner = owner,
                possessionConfidence = possession.confidence,
                ballX = ballX,
                ballY = ballY,
                deltaX = deltaX,
                deltaY = deltaY,
                distance = distance,
                trapBallSpeed = trapBallSpeed,
                trapBand = trapBand,
                trapMargin = trapMargin
            )
        }
        prevChaseActive = false
        // The trap path below still requires possession + hasBall:
        // the old top gates guaranteed both, and this split keeps that
        // exact conjunction for the trap path. Only the chase arm above
        // runs on possession-lost frames.
        if (!possessionHeld || !frame.hasBall) return null

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
         * No68-B-D33: this is a proximity signal (1.0 = ball on the
         * owner's feet), not defender pressure -- the old name
         * `pressure` hid that, and ActiveGestureController feeds the
         * same engine parameter with raw ball distance, i.e. the
         * opposite sense (far ball = larger input). The parameter is
         * renamed in MagneticFeetEngine.stabilize() to match; the AGC
         * call site is a queue item (QB-3), not this file.
         */
        val proximitySignal =
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
                proximity = proximitySignal,
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
         * No68-B-D20: shape the weighted authority before the requested
         * 0.9..1.2 bounds so the result does not immediately hit the cap
         * for every moderately high touch value. The old bend
         * w / (1 + 0.20w) still saturated: touch >= 3.86 already gave
         * authority 1.0, and the 60 px ownership gate holds steady-state
         * touch >= 5.24, so authority was 1.0 on EVERY steady-state
         * frame and the trap boost was invisible -- ConAI ranked this
         * engine by confidence alone. w / (1 + w) keeps the same
         * requested constants and monotonicity but never saturates to
         * the cap (touch 12 -> 0.795, x1.25 boost -> 0.994).
         */
        val shapedAuthority =
            weightedAuthority /
                (
                    1.0f +
                        weightedAuthority
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
        // SPLENDOR_P9_MF_AUTHORITY: the old 0.9f floor forced authority
        // into [0.9, 1.0] on every contribution, so arbitration could
        // never rank it -- fabricated inflation. Floor 0.0f: authority
        // now reflects live proximity/confidence.
        // No68-B-D25: one clamp. EngineContribution.weight clamps
        // authority to 0..1 anyway, so the requested 1.2 internal cap
        // survives only as minOf(cap, 1.0f) -- the old double coerceIn
        // said the same thing twice.
        val authority =
            (shapedAuthority * trapBoost)
                .coerceIn(
                    0.0f,
                    minOf(cap, 1.0f)
                )

        /*
         * Vision-space target on the owner->ball ray: the owner plus
         * the unit direction times the travel. chooseRequest() derives
         * the joystick deflection as (target - aimOrigin), with
         * aimOrigin = owner (set on the contribution below), so the
         * direction that reaches the screen is exactly directionX/Y.
         * (No68-B-D18: the old target was JOYSTICK_ORIGIN + dir*travel
         * while the aim origin was the owner, so the deflection was
         * (joystickOrigin - owner) + dir*travel -- for an owner at
         * (1200,300) and a ball to his right the stick pushed LEFT.)
         * Travel magnitude itself does not survive chooseRequest() --
         * it normalises the vector -- so travel only positions this
         * point, it never sets the swipe length.
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

        // SPLENDOR_P10_TRAP_TRAVEL: pre-position (ball incoming but
        // outside the 0..6-frame trap window) halves the swipe toward
        // MIN_TRAVEL instead of a long chase swipe.
        val trapTravel = if (prePosition) MIN_TRAVEL + (travel - MIN_TRAVEL) * 0.5f else travel

        val targetX =
            (
                owner.x +
                    (
                        directionX *
                            trapTravel
                    )
            ).coerceIn(
                0.0f,
                SCREEN_MAX_X
            )

        val targetY =
            (
                owner.y +
                    (
                        directionY *
                            trapTravel
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

        // SPLENDOR_P10_TRAP_LOG: one line on CLOSING window-enter
        // only (not per frame); authority here is the final boosted
        // value, so the log proves the boost that arbitration saw.
        // No68-B-D31: explicit Locale.US -- the bare "%.1f".format
        // used the device locale, so a comma-decimal locale wrote
        // unparseable log lines.
        if (enteredTrapWindow) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "TRAP window=CLOSING closingPxF=" + "%.1f".format(Locale.US, closingSpeed) +
                    " dist=" + "%.0f".format(Locale.US, distance) +
                    " defDist=" + "%.0f".format(Locale.US, nearestDefenderDist) +
                    " band=" + trapBand +
                    " ttcF=" + "%.1f".format(Locale.US, timeToContactF) +
                    " auth=" + "%.2f".format(Locale.US, authority),
                "MagneticFeet")
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = duration,
            // No68-B-D19: the aim origin is this contribution's own
            // owner, not whatever frame.ownerId resolves to in
            // chooseRequest(). The contributor reads the unfiltered
            // SceneTracker snapshot while frame.entities is filtered by
            // FrameAssembler, so the owner can be missing from entities
            // and the old fallback (mover = null) dropped the request.
            aimOriginX = owner.x,
            aimOriginY = owner.y,
            aimEntityId = owner.id
        )
    }


    /*
     * CHASE ARM: the chaser on a possession-lost frame is the nearest
     * user-team outfield player to the ball in the unfiltered
     * SceneTracker snapshot — the same source the trap path reads its
     * owner from. (frame.entities is FrameAssembler-filtered.) A
     * teammate ahead of the knocked ball is a valid chaser too: the
     * nearest user player is the one who can reach the ball first.
     */
    private fun nearestChaser(
        players: List<TrackedPlayer>,
        ballX: Float,
        ballY: Float
    ): TrackedPlayer? {
        return players
            .filter { it.isUserTeam && !it.isGoalkeeper && it.confidence > 0.0f }
            .minByOrNull { p ->
                hypot((p.x - ballX).toDouble(), (p.y - ballY).toDouble())
            }
    }

    /*
     * CHASE ARM (#43): the dash knock-on recovery.
     *
     * The full glue (ball stuck to the feet through a full-speed
     * sprint) is NOT achievable through the app's inputs as they
     * stand: the app cannot modulate the stick while Ed holds it
     * (the RDL never-fight gate holds a MOVE whenever the joystick
     * is held), there is no Dash-press lever anywhere in the repo,
     * and no close-control/R2 button exists. The knock-on is
     * Konami's sprint physics, not the app's doing. This arm does
     * NOT promise the glue — it is the recovery: chase the knocked
     * ball down while a defender threatens it, so the carrier
     * re-collects it and the existing D11 sharp cut (defender inside
     * tackle radius ~23 px + dashHeld -> SKILL_MOVE) can fire. The
     * arm feeds D11; it never duplicates it.
     *
     * Gates, in order (fail open — any miss returns null):
     *  (i) dash ON — from the ring state (frame.isDashing, #45
     *      plumbing). The ring reader (#39) reports CYAN when the
     *      controlled player is dashing / in possession and PALE
     *      otherwise; the state is a dash/possession PROXY, never
     *      "Dash pressed" as fact (the trigger is UNVERIFIED
     *      on-device). Labelled as ring here AND in the CHASE log
     *      line ("dash=ring").
     *  (ii) chase band 18..120 px on a LEAVING frame (ball moving
     *      away, speed >= 5 px/frame — a frozen/stale ball reads
     *      SETTLED, never LEAVING, so no stale-distance chase).
     *  (iii) PRESS — nearest defender within 45 px of the BALL
     *      (same margin the trap band uses) or the owner already
     *      pressed (trapBand PRESS/HOT).
     *  (iv) stick window OPEN — the same conditions the RDL gates
     *      check before emitting: the user is touching
     *      (authorizing), slot 0 (joystick) is NOT in the hold mask,
     *      and the proposed direction does not oppose the knob
     *      reader's held direction (dot < 0). A contribution the RDL
     *      would hold is never offered.
     * The MOVE shape is the trap path's shape (target on the
     * owner->ball ray, travel per the inverted rule, duration
     * 16-24 ms, authority from stabilize()); the confidence blend
     * is the same formula with possession.confidence as-is (0 on a
     * possession-lost frame, so the chase honestly ranks below a
     * clean trap). The arm NEVER emits a MOVE opposing the knob
     * reader's held direction, and it does NOT prevent rough-tackle
     * dispossessions (tackles still happening is a falsifier, not a
     * bug).
     */
    private fun tryChaseArm(
        frame: RuntimeFrame,
        owner: TrackedPlayer,
        possessionConfidence: Float,
        ballX: Float,
        ballY: Float,
        deltaX: Float,
        deltaY: Float,
        distance: Float,
        trapBallSpeed: Float,
        trapBand: String,
        trapMargin: Float
    ): EngineContribution? {
        // (i) DASH — ring state (frame.isDashing, #45 plumbing).
        // A dash/possession PROXY, never "Dash pressed" as fact.
        val dashOn = frame.isDashing
        if (!dashOn) {
            prevChaseActive = false
            return null
        }
        // (ii) CHASE BAND.
        if (
            !distance.isFinite() ||
            distance < CHASE_MIN_DISTANCE ||
            distance > CHASE_MAX_DISTANCE
        ) {
            prevChaseActive = false
            return null
        }
        // Ball must be live: LEAVING already requires trapBallSpeed
        // >= 5, so a frozen/stale ball cannot reach this arm.
        if (!trapBallSpeed.isFinite() || trapBallSpeed < 5f) {
            prevChaseActive = false
            return null
        }
        // (iii) PRESS — defender threatens the ball.
        val nearestDefenderToBallDist = frame.entities.filter { !it.teammate }
            .minOfOrNull { e -> hypot((e.x - ballX).toDouble(), (e.y - ballY).toDouble()).toFloat() }
            ?: Float.MAX_VALUE
        val defenderThreat = nearestDefenderToBallDist <= CHASE_PRESS_DISTANCE + trapMargin ||
            trapBand == "PRESS" || trapBand == "HOT"
        if (!defenderThreat) {
            prevChaseActive = false
            return null
        }
        // (iv) STICK WINDOW — same conditions the RDL gates check.
        if (!UserTouchState.isUserTouchAuthorizing()) {
            prevChaseActive = false
            return null
        }
        if (frame.userIntentMask and UserIntentVision.slotBit(0) != 0) {
            prevChaseActive = false
            return null
        }
        val directionX = deltaX / distance
        val directionY = deltaY / distance
        val userDir = frame.userIntentDirection
        if (userDir != null && userDir.magnitude >= com.assistant.overlay.interceptor.JoystickKnobReader.MIN_DIRECTION_MAGNITUDE) {
            val dot = directionX * userDir.dirX + directionY * userDir.dirY
            if (dot < 0f) {
                prevChaseActive = false
                return null
            }
        }
        // MOVE shape: the trap path's shape (same constants, same
        // inverted travel rule, same 16-24 ms durations, authority
        // from stabilize()). No trap boost on a LEAVING frame
        // (closingSpeed < 5 there by definition), so the boost is 1.0.
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
        val proximitySignal =
            (
                proximity *
                    100.0f
            ).toInt().coerceIn(
                0,
                100
            )
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
                proximity = proximitySignal,
                strength = strength
            )
        val rawAuthority =
            (
                result.touchRetention /
                    5.0f
            ) * 1.2f
        val weightedAuthority =
            rawAuthority *
                (
                    (1.5f + 1.2f) / 2.0f
                )
        val shapedAuthority =
            weightedAuthority /
                (
                    1.0f +
                        weightedAuthority
                )
        val authority =
            shapedAuthority
                .coerceIn(
                    0.0f,
                    minOf(1.2f, 1.0f)
                )
        val confidence =
            (
                (frame.confidence * 0.30f) +
                    (possessionConfidence * 0.35f) +
                    (owner.confidence * 0.20f) +
                    (proximity * 0.15f)
            ).coerceIn(
                0.0f,
                1.0f
            )
        val travel =
            MAX_TRAVEL -
                (
                    (
                        MAX_TRAVEL -
                            MIN_TRAVEL
                    ) * proximity
                )
        val targetX =
            (
                owner.x +
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
                owner.y +
                    (
                        directionY *
                            travel
                    )
            ).coerceIn(
                0.0f,
                SCREEN_MAX_Y
            )
        val duration =
            when {
                proximity >= 0.75f -> 16L
                proximity >= 0.45f -> 20L
                else -> 24L
            }
        // CHASE log: one line per chase entry, not per frame.
        // "dash=ring" — the dash signal is the ring state
        // (frame.isDashing, #45 plumbing), a dash/possession PROXY,
        // never "Dash pressed" as fact.
        if (!prevChaseActive) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "CHASE dist=" + "%.0f".format(Locale.US, distance) +
                    " defBall=" + "%.0f".format(Locale.US, nearestDefenderToBallDist) +
                    " band=" + trapBand +
                    " dash=ring" +
                    " auth=" + "%.2f".format(Locale.US, authority),
                "MagneticFeet")
        }
        prevChaseActive = true
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = duration,
            aimOriginX = owner.x,
            aimOriginY = owner.y,
            aimEntityId = owner.id
        )
    }

    override fun reset() {
        // No68-B-D23: the engine reset never touched this file's own
        // prevTrapWindow, so the first CLOSING window after a reset
        // could go unlogged when the stale value was already CLOSING.
        prevTrapWindow = "none"
        prevChaseActive = false
        MagneticFeetEngine.reset()
    }
}
