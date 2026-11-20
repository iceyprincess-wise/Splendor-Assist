package com.assistant

/**
 * Free booster engine — queue item 4 (Ed 2026-11-20 ~15:10 WAT).
 *
 * PURE DECISION CORE. No Android imports, no singletons, no I/O: every
 * input arrives as an explicit value, every output is a plain decision.
 * The Android side (radios, permissions, wake lock, log mirror) lives in
 * FreeBoosterController, which calls decide() / onPoll() and acts on the
 * result. This file is the part the harness can execute (g36a).
 *
 * GOVERNING PRINCIPLE (Ed, voice note ~15:13 WAT): the booster works on
 * ALL local issues — dimming, hitches, spikes, hanging, lagging, RAM
 * pressure — IN the app. It must NEVER throttle gameplay engines to fix
 * lag: when the game lags, gameplay engines CONTINUE at full effect and
 * the booster takes the lag on itself. This core therefore decides only
 * two things: whether the booster should shed ITS OWN work, and whether
 * the radio keepalive should run at its fastest cadence. It has no input
 * that could slow a gameplay engine and no output that does.
 *
 * WHAT THIS CORE NEVER DOES (by construction — there is no code path for
 * it): change a capture interval, gate a gesture, filter a contribution,
 * suppress a MOVE, or touch the HOLD / brake chains. Those chains are
 * owned by ActionWindowEngine / LoadShedCaptureBrakeEngine and are left
 * exactly as they are.
 *
 * SIGNAL VALUES ARE NEVER INVENTED: signalLevel -1 / signalDbm 0 mean
 * "unknown" (permission not granted or no reading yet). Unknown never
 * triggers a shed — only a measured level 0..1 does.
 */
object FreeBoosterEngine {

    /** One poll's worth of live readings, as plain values. */
    data class Readings(
        val rttMs: Float,
        val jitterMs: Float,
        val lossPct: Float,
        val quality: String,
        val signalLevel: Int, // -1 unknown, 0..4 measured bars
        val signalDbm: Int, // 0 unknown, otherwise negative dBm
        val transport: String,
        val congested: Boolean,
        val verdict: String,
        val jitterTolMs: Float
    )

    /** What the controller should do this poll. */
    data class Decision(
        val shedOwnWork: Boolean,
        val keepaliveBoost: Boolean,
        val reasons: List<String>
    )

    /** Match edge reported to the controller. */
    enum class MatchEdge { STARTED, ENDED }

    // ---- match-edge machine (poll-driven, 5 s per poll) ----
    // STARTED fires on the FIRST in-game poll (the foreground signal
    // itself is already debounced 2 s by VisionTrust). ENDED fires only
    // after OFF_POLLS_TO_END consecutive off-game polls (10 s grace), so
    // a transient foreground flicker never tears down the transport
    // request and the wake lock mid-match.
    private const val OFF_POLLS_TO_END = 2

    // ---- shed triggers (stated tuning, not measured constants) ----
    // Loss and jitter bars are the ActionWindowEngine bars (HOLD above
    // 10% loss, HOLD above 2x tolerance) — the booster sheds its own work
    // under exactly the conditions the app already calls degraded, so it
    // never invents a new definition of "bad link".
    private const val LOSS_SHED_PCT = 10f
    private const val JITTER_SHED_MULT = 2f
    // Signal level 0..1 (0 or 1 bar) is the weak-signal shed trigger.
    // -1 (unknown) never triggers.
    private const val WEAK_SIGNAL_MAX_LEVEL = 1

    @Volatile private var inMatch = false
    @Volatile private var offStreak = 0

    // ---- live signal values, written by the controller's reader ----
    @Volatile var signalLevel: Int = -1; private set
    @Volatile var signalDbm: Int = 0; private set

    fun updateSignal(level: Int, dbm: Int) {
        signalLevel = level
        signalDbm = dbm
    }

    // ---- counters (booster's own effect, for the BOOSTER log) ----
    @Volatile var matchesBoosted: Long = 0L; private set
    @Volatile var shedsApplied: Long = 0L; private set
    @Volatile var routineBytesDropped: Long = 0L; private set
    @Volatile var routineLinesDropped: Long = 0L; private set

    fun noteMatchStarted() { matchesBoosted++ }
    fun noteShed(lines: Long, bytes: Long) {
        shedsApplied++
        routineLinesDropped += lines
        routineBytesDropped += bytes
    }

    /**
     * Match edge for this poll. STARTED on the first in-game poll after
     * being off; ENDED after [OFF_POLLS_TO_END] consecutive off-game
     * polls; null otherwise (including a single off-game poll, which is
     * only a flicker until proven otherwise). The dwell is counted in
     * polls, not wall time — the controller polls on a fixed cadence, so
     * no timestamp is needed here.
     */
    fun onPoll(inGame: Boolean): MatchEdge? {
        if (inGame) {
            offStreak = 0
            if (!inMatch) {
                inMatch = true
                return MatchEdge.STARTED
            }
            return null
        }
        if (!inMatch) {
            offStreak = 0
            return null
        }
        offStreak++
        if (offStreak >= OFF_POLLS_TO_END) {
            inMatch = false
            offStreak = 0
            return MatchEdge.ENDED
        }
        return null
    }

    fun isInMatch(): Boolean = inMatch

    /**
     * Shed decision. Sheds ONLY in-match: off-match there is no game to
     * protect, so the app's own work runs normally. In-match, sheds when
     * the link is degraded by any already-established measure — verdict
     * HOLD, loss above the HOLD bar, jitter above twice the carrier
     * tolerance, congestion flagged, or a measured weak signal. Every
     * trigger is named in [Decision.reasons] so the log line says why.
     */
    fun decide(inGame: Boolean, readings: Readings): Decision {
        if (!inGame) return Decision(shedOwnWork = false, keepaliveBoost = false, reasons = emptyList())
        val reasons = mutableListOf<String>()
        if (readings.verdict == "HOLD") reasons += "HOLD"
        if (readings.lossPct > LOSS_SHED_PCT) reasons += "loss>${LOSS_SHED_PCT.toInt()}%"
        if (readings.jitterMs > readings.jitterTolMs * JITTER_SHED_MULT) reasons += "jitter>2xTol"
        if (readings.congested) reasons += "congested"
        if (readings.signalLevel in 0..WEAK_SIGNAL_MAX_LEVEL) reasons += "signal${readings.signalLevel}bar"
        return Decision(
            shedOwnWork = reasons.isNotEmpty(),
            keepaliveBoost = true,
            reasons = reasons
        )
    }

    /** One BOOSTER readings line: before/after proof, not an assertion. */
    fun snapshot(readings: Readings, inGame: Boolean): String {
        val signal = if (readings.signalLevel < 0) "unknown" else "${readings.signalLevel}bar/${readings.signalDbm}dBm"
        return "BOOSTER match=$inGame" +
            " rtt=${readings.rttMs.toInt()}ms" +
            " jitter=${readings.jitterMs.toInt()}ms" +
            " loss=${readings.lossPct.toInt()}%" +
            " quality=${readings.quality}" +
            " signal=$signal" +
            " transport=${readings.transport}" +
            " congested=${readings.congested}" +
            " verdict=${readings.verdict}" +
            " matches=$matchesBoosted" +
            " sheds=$shedsApplied" +
            " droppedLines=$routineLinesDropped" +
            " droppedBytes=$routineBytesDropped"
    }

    /** Test hook: clears match state, signal, and counters. */
    fun reset() {
        inMatch = false
        offStreak = 0
        signalLevel = -1
        signalDbm = 0
        matchesBoosted = 0L
        shedsApplied = 0L
        routineLinesDropped = 0L
        routineBytesDropped = 0L
    }
}
