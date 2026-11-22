package com.assistant

import com.assistant.runtime.MatchPhase

/**
 * G-C — Match-State Engine (engine-ideas §4, Ed D12: approved ONLY at
 * near-zero added load; if it adds load, it is out).
 *
 * Gap (absence PROVEN, repo map G10): OverlayService.processBitmapForOCR
 * -> UiStateObserver.observeText() had no consumer for score/clock text,
 * and RuntimeFrame carried no score/clock/phase field — the app played
 * 0-0 minute 1 the same as 0-1 minute 89.
 *
 * Zero-added-load design: this engine parses the OCR text that is
 * ALREADY produced once per ~1.5 s tick. No new capture, no new OCR
 * pass, no per-frame work — observeText() hands it the same string it
 * already received, and FrameAssembler copies the last parsed state
 * (a volatile read) into the frame. Per-frame cost: one volatile read
 * in FrameAssembler + the contributors reading frame fields they
 * already read. That is the D12 condition, met by construction.
 *
 * Parsing discipline (false positives are worse than no state): an
 * update is accepted ONLY when the same OCR text carries BOTH a
 * score-like "N - N" AND a minute like "67'". A lone number is never
 * a score. A lone minute never moves the score. "FT" / "full time"
 * marks FULLTIME. State only ever moves forward in minute (a parsed
 * minute below the stored one is ignored unless the score also
 * changed — a new half can re-show an early minute after a goal).
 * MatchPhase lives in com.assistant.runtime (RuntimeFrame.kt) — the
 * frame carries it, so the engine uses the same enum, not a copy.
 */
data class MatchState(
    val minute: Int = 0,
    val scoreFor: Int = 0,
    val scoreAgainst: Int = 0,
    val phase: MatchPhase = MatchPhase.UNKNOWN,
    val known: Boolean = false
)

object MatchStateEngine {

    private val SCORE_REGEX = Regex("(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})")
    private val MINUTE_REGEX = Regex("(\\d{1,3})\\s*['’]")
    private val FULLTIME_REGEX = Regex("(?i)\\bft\\b|full[- ]?time")

    @Volatile private var state = MatchState()

    fun current(): MatchState = state

    /** Parse already-OCR'd text. Pure regex over the given string; no I/O. */
    fun observe(text: String): MatchState {
        if (text.isBlank()) return state
        val fulltime = FULLTIME_REGEX.containsMatchIn(text)
        val minuteMatch = MINUTE_REGEX.find(text)
        val scoreMatch = SCORE_REGEX.find(text)
        // Both halves of the evidence are required. A score without a
        // minute is a stray number (odds, stats); a minute without a
        // score is a stray clock.
        if (scoreMatch == null || minuteMatch == null) {
            if (fulltime && state.known) {
                val done = state.copy(phase = MatchPhase.FULLTIME)
                state = done
                return done
            }
            return state
        }
        val minute = minuteMatch.groupValues[1].toIntOrNull() ?: return state
        if (minute < 0 || minute > 120) return state
        val forGoals = scoreMatch.groupValues[1].toIntOrNull() ?: return state
        val againstGoals = scoreMatch.groupValues[2].toIntOrNull() ?: return state
        if (forGoals > 15 || againstGoals > 15) return state
        val prev = state
        // Minute must not run backwards within the same scoreline —
        // OCR misreads a digit far more often than the clock rewinds.
        if (prev.known && minute < prev.minute &&
            forGoals == prev.scoreFor && againstGoals == prev.scoreAgainst
        ) return prev
        val phase = when {
            fulltime || minute >= 90 -> MatchPhase.FULLTIME
            minute >= 61 -> MatchPhase.LATE
            minute >= 31 -> MatchPhase.MID
            else -> MatchPhase.EARLY
        }
        val next = MatchState(
            minute = minute,
            scoreFor = forGoals,
            scoreAgainst = againstGoals,
            phase = phase,
            known = true
        )
        state = next
        return next
    }

    fun reset() {
        state = MatchState()
    }
}
