package com.assistant.adapter.smartassist

import kotlin.math.abs

/*
 * OVERLOAD PLAYSTYLE ENGINE
 *
 * Tactical model: create or exploit numerical superiority in one zone.
 *   - Own overload  -> attack through the loaded zone.
 *   - Opponent overload -> switch play to the isolated far side.
 *
 * Pure calculation. Takes primitives only, returns a bounded result.
 * It never reads stores and never calls another engine, so it can be
 * rewritten at any scale without touching the rest of the runtime.
 */

enum class OverloadZone { LEFT_WING, CENTRAL, RIGHT_WING, NONE }

enum class OverloadMode { DEFENSIVE_SWARM, ATTACKING_EXPLOIT, IDLE }

data class OverloadPlaystyleResult(
    val mode: OverloadMode,
    val zone: OverloadZone,
    val overloadStrength: Float,
    val opponentOverload: Boolean,
    val switchPlayRecommended: Boolean,
    val exploitX: Float,
    val exploitY: Float,
    val confidence: Float
)

object OverloadPlaystyleEngine {

    private const val DEFAULT_PITCH_W = 1650f
    private const val DEFAULT_PITCH_H = 720f
    private const val ADVANCE_RATIO = 0.18f
    private const val OPPONENT_PRESSURE_THRESHOLD = 0.60f

    private var analyzeCalls: Long = 0L
    private var lastZone: String = "not analyzed yet"
    private var lastStrength: Float = 0f
    private var lastSwitchPlay: Boolean = false
    private var lastUpdatedMs: Long = 0L

    fun analyze(
        ballX: Float,
        ballY: Float,
        playerCount: Int,
        opponentCount: Int,
        defenderDensity: Float,
        laneConfidence: Float,
        weHavePossession: Boolean = false,
        zoneOurs: Int = 0,
        zoneTheirs: Int = 0,
        pitchWidth: Float = DEFAULT_PITCH_W,
        pitchHeight: Float = DEFAULT_PITCH_H
    ): OverloadPlaystyleResult {

        val width = pitchWidth.coerceAtLeast(1f)
        val height = pitchHeight.coerceAtLeast(1f)

        val total = playerCount.coerceAtLeast(0)
        val theirs = opponentCount.coerceIn(0, total)
        val ours = (total - theirs).coerceAtLeast(0)

        // Signed numerical balance in view, -1 (they dominate) .. +1 (we dominate)
        // Prefer real zone counts when supplied; fall back to whole-view totals.
        val zoneTotal = zoneOurs + zoneTheirs
        val balance = if (zoneTotal > 0)
            ((zoneOurs - zoneTheirs).toFloat() / zoneTotal).coerceIn(-1f, 1f)
        else if (total > 0)
            ((ours - theirs).toFloat() / total).coerceIn(-1f, 1f)
        else 0f

        val pressure = defenderDensity.coerceIn(0f, 1f)
        val opponentOverload = balance < 0f || pressure >= OPPONENT_PRESSURE_THRESHOLD

        // Zone is read from lateral ball position (landscape pitch).
        val zone = when {
            total <= 0 -> OverloadZone.NONE
            ballY < height * 0.33f -> OverloadZone.LEFT_WING
            ballY > height * 0.67f -> OverloadZone.RIGHT_WING
            else -> OverloadZone.CENTRAL
        }

        // Strength blends numerical edge with how contested the zone is.
        val contested = if (opponentOverload) pressure else (1f - pressure)
        val overloadStrength =
            (abs(balance) * 0.65f + contested * 0.35f).coerceIn(0f, 1f)

        val switchPlay = opponentOverload && zone != OverloadZone.NONE

        val advance = width * ADVANCE_RATIO
        val swarm = !weHavePossession
        val exploitX = if (swarm) ballX.coerceIn(0f, width)
                       else (ballX + advance).coerceIn(0f, width)

        // Switch play mirrors to the far side; otherwise press the loaded zone.
        val exploitY = if (swarm) ballY.coerceIn(0f, height)
        else if (switchPlay) {
            (height - ballY).coerceIn(0f, height)
        } else {
            when (zone) {
                OverloadZone.LEFT_WING -> (ballY - height * 0.05f).coerceIn(0f, height)
                OverloadZone.RIGHT_WING -> (ballY + height * 0.05f).coerceIn(0f, height)
                else -> ballY.coerceIn(0f, height)
            }
        }

        val confidence =
            (laneConfidence.coerceIn(0f, 1f) * 0.7f + overloadStrength * 0.3f)
                .coerceIn(0f, 1f)

        // DEFENSIVE SWARM is the primary playstyle: opponent holds the ball, our
        // players collapse on the carrier to deny build-up. ATTACKING_EXPLOIT is
        // the hybrid inverse used when we regain possession.
        val mode = when {
            zone == OverloadZone.NONE -> OverloadMode.IDLE
            !weHavePossession -> OverloadMode.DEFENSIVE_SWARM
            else -> OverloadMode.ATTACKING_EXPLOIT
        }

        record(zone.name, overloadStrength, switchPlay)

        return OverloadPlaystyleResult(
            mode = mode,
            zone = zone,
            overloadStrength = overloadStrength,
            opponentOverload = opponentOverload,
            switchPlayRecommended = switchPlay,
            exploitX = exploitX,
            exploitY = exploitY,
            confidence = confidence
        )
    }

    @Synchronized
    private fun record(zone: String, strength: Float, switchPlay: Boolean) {
        analyzeCalls += 1L
        lastZone = zone
        lastStrength = strength
        lastSwitchPlay = switchPlay
        lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun overloadRuntimeSnapshot(): Map<String, Any> = mapOf(
        "analyzeCalls" to analyzeCalls,
        "lastZone" to lastZone,
        "lastStrength" to lastStrength,
        "lastSwitchPlay" to lastSwitchPlay,
        "lastUpdatedMs" to lastUpdatedMs
    )

    @Synchronized
    fun reset() {
        analyzeCalls = 0L
        lastZone = "not analyzed yet"
        lastStrength = 0f
        lastSwitchPlay = false
        lastUpdatedMs = 0L
    }
}
