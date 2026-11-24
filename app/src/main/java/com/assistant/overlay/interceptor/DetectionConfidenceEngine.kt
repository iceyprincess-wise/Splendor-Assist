package com.assistant.overlay.interceptor

/**
 * Phase 2, engine #7 — DetectionConfidenceEngine (pure Kotlin, no Android, no native).
 *
 * Kills G2: per-source reliability + an ADAPTIVE SAVE threshold, replacing
 * ShotAnticipationEngine's fixed priority >= 100 bar for fused shots.
 *
 * The gap (QF-8): ShotAnticipationEngine.kt evaluates a ThreatDecision and
 * returns SAVE only at priority >= 100; NEAR_POST / FAR_POST / CENTER
 * threats at priority 75-99 fall through to TRACK (keeper stands still),
 * because the old catch-all at >= 75 was scoped to CROSS / LONG_BALL
 * only (No68-F-D16) — the router's INTERCEPT arm cannot act on a
 * post/centre threat, so those threats read TRACK honestly but never
 * save. The fixed bar treats a corroborated multi-source shot and a
 * lone weak signal identically. This engine does not: a fused shot
 * backed by 2+ agreeing sources saves at a lower bar than a lone
 * source, and the old >= 100 bar is preserved as a floor, never raised.
 *
 * ROUTED DECISION (James, verifying fusion, routed it here): does the
 * gauge's per-pixel GaugeHit.score (0.5-1.0 match strength) count
 * toward the SAVE bar?
 *
 * DECISION: NO. Evidence:
 *  - score is SINGLE-PIXEL strength: 1f - 0.5f*d for one pixel's HSV
 *    distance d. One bright pixel is not a gauge bar — a gauge is a
 *    bar of many pixels, and one pixel can be a jersey, a boot, or
 *    skin. A high score says "this pixel looks gauge-coloured", not
 *    "a gauge fired".
 *  - confidence is MODEL EVIDENCE: 0.5 on the seed model, rising to
 *    1.0 after 32 confirmed learned hits (AdaptiveGaugeClassifier:
 *    confidence() = 0.5 + 0.5*(samples/32)). It says how much the
 *    model has learned this device's gauge colours.
 *  - Fusion already decided isShot on confidence (STRONG_THRESHOLD
 *    0.7). Re-barring SAVE on score would be a SECOND fusion
 *    threshold — explicitly forbidden by the brief — and would let
 *    a single bright pixel override the learned model's verdict.
 *  - The fresh-seed lone gauge hit (confidence 0.5) never reaches
 *    this engine as a fused shot at all: fusion lists GAUGE as a
 *    source only at confidence >= 0.7 (13 confirmed hits:
 *    0.5 + 0.5*(13/32) = 0.703). Score cannot rescue it here, and
 *    must not: the correct fix for a fresh gauge is learning, not
 *    lowering the bar on pixel brightness.
 *  Score is therefore recorded in diagnostics() only. It never gates
 *  SAVE. This engine's adaptive bar keys on fused confidence (which
 *  already embeds gauge confidence), source count, and the
 *  anticipation priority.
 *
 * RELIABILITY RECORD — own record, NOT EngineCreditStore. Evidence:
 * EngineCreditStore is per-ENGINE credit fed by ActionOutcomeVerifier
 * macro verdicts (did the EXECUTED action succeed), persisted in
 * SharedPreferences with idle decay. This engine needs per-SOURCE
 * firing/corroboration counts (did the source agree with another
 * source on the same frame) — a different signal (agreement, not
 * outcome), session-only, three fixed entries. Persisting it would
 * carry a stale agreement rate across matches; the store's
 * persistence would buy nothing. The record here is in-memory,
 * bounded (see RECORD_CAP), and never persisted.
 *
 * ADAPTIVE THRESHOLDS (derived from the fusion confidence scale,
 * never magic numbers without a source):
 *  - Fusion confidence for a lone strong source is that source's own
 *    confidence (0.7..1.0); for 2 sources it is max + 0.1, for 3 it
 *    is max + 0.2, capped at 1.0 (CORROBORATION_BONUS 0.1f). So a
 *    corroborated shot's confidence is >= 0.8 by construction.
 *  - CORROBORATED_SAVE_CONF = 0.7f = DetectionFusionEngine
 *    .STRONG_THRESHOLD: any corroborated fused shot meets it.
 *  - LONE_SAVE_CONF = 0.9f: a lone source saves only when
 *    near-certain. A lone trajectory at 1.0 (both aim checks fired)
 *    passes; a lone trajectory at 0.7 (one check only) does not. A
 *    lone gauge passes only once learned to 0.9 (26 hits:
 *    0.5 + 0.5*(26/32) = 0.906).
 *  - SAVE_PRIORITY_FLOOR = 75: the QF-8 band's lower edge. A fused
 *    shot below priority 75 never saves — the anticipation side is
 *    too weak, whatever the fusion says. This is the old TRACK
 *    band's floor, reused as the adaptive floor.
 *  - LEGACY_SAVE_PRIORITY = 100: the old fixed bar, preserved. A
 *    fused shot at priority >= 100 saves on any lone strong source
 *    at >= 0.7 — exactly as before. The adaptive rules only ADD
 *    saves below 100; they never take one away.
 *
 * FAIL OPEN: any invalid input (non-finite fused confidence,
 * negative priority, isShot=true with empty sources) returns TRACK
 * — the current behaviour — never a crash, never a forced SAVE.
 * A genuine no-shot (fused.isShot false, consistent) returns
 * NO_SAVE: nothing to save from. PANIC is not emitted here:
 * priority >= 150 is answered SAVE by the legacy rule and the
 * caller's PanicSave path owns the panic decision.
 */
object DetectionConfidenceEngine {

    /** Outcome of the adaptive SAVE/TRACK decision. */
    enum class SaveDecision { SAVE, TRACK, NO_SAVE }

    /** Per-source firing/corroboration record. Counts saturate (see below). */
    data class SourceReliability(
        val fired: Int = 0,
        val corroborated: Int = 0
    ) {
        /**
         * Agreement rate with a Laplace prior: (corroborated + 1) /
         * (fired + 2). An unobserved source reads 0.5 (unknown, not
         * bad); a source corroborated every time reads near 1.0.
         */
        val agreement: Float
            get() = (corroborated + 1).toFloat() / (fired + 2).toFloat()
    }

    /** Corroborated fused shot saves at the fusion strong bar. */
    const val CORROBORATED_SAVE_CONF: Float = DetectionFusionEngine.STRONG_THRESHOLD

    /** A lone source saves only when near-certain. */
    const val LONE_SAVE_CONF: Float = 0.9f

    /** QF-8 band lower edge: below this priority no fused shot saves. */
    const val SAVE_PRIORITY_FLOOR: Int = 75

    /** The old fixed SAVE bar, preserved exactly. */
    const val LEGACY_SAVE_PRIORITY: Int = 100

    /**
     * Counter bound: on reaching this many observations both counters
     * for a source are halved (ratio preserved, growth bounded).
     * Three fixed map entries + saturating counters = no unbounded
     * growth, ever.
     */
    private const val RECORD_CAP: Int = 10_000

    private val record = mutableMapOf<DetectionFusionEngine.ShotSource, SourceReliability>()

    /**
     * Record one fused hypothesis in the per-source reliability
     * record: each listed source fired once; a source that fired
     * alongside at least one other source was corroborated once.
     * Never throws; a NO_SHOT hypothesis records nothing.
     */
    @Synchronized
    fun record(fused: DetectionFusionEngine.FusedShotHypothesis) {
        try {
            if (!fused.isShot || fused.sources.isEmpty()) return
            val corroborated = fused.sources.size >= 2
            for (source in fused.sources) {
                val prev = record[source] ?: SourceReliability()
                var fired = prev.fired + 1
                var corrob = prev.corroborated + if (corroborated) 1 else 0
                if (fired >= RECORD_CAP) {
                    fired /= 2
                    corrob /= 2
                }
                record[source] = SourceReliability(fired = fired, corroborated = corrob)
            }
        } catch (_: Throwable) {
            // Reliability recording must never break the save path.
        }
    }

    /** Agreement rate for one source (0.5 prior when unobserved). */
    @Synchronized
    fun reliability(source: DetectionFusionEngine.ShotSource): Float {
        return (record[source] ?: SourceReliability()).agreement
    }

    /** Snapshot of the per-source record for diagnostics. */
    @Synchronized
    fun diagnostics(): Map<DetectionFusionEngine.ShotSource, SourceReliability> {
        return record.toMap()
    }

    /**
     * Adaptive SAVE vs TRACK decision for one fused hypothesis.
     *
     * [fused] is DetectionFusionEngine.fuse()'s output (isShot
     * already decided there — this engine does NOT re-decide it);
     * [anticipationPriority] is the ThreatDecision priority the
     * fixed bar used (ShotAnticipationEngine: >= 100 SAVE,
     * 75-99 post/centre TRACK).
     *
     * Rules, in order:
     *  1. Invalid input (non-finite confidence, negative priority,
     *     isShot with empty sources) -> TRACK (fail open).
     *  2. Not a shot (isShot false) -> NO_SAVE.
     *  3. Fused confidence below the fusion strong bar (< 0.7) ->
     *     TRACK. Fusion guarantees >= 0.7 for a real fused shot;
     *     anything below it is a mismatched build, and a lone
     *     fresh-seed gauge (0.5) must never save on model
     *     evidence alone.
     *  4. Priority below the QF-8 floor (< 75) -> TRACK.
     *  5. Legacy bar: priority >= 100 -> SAVE (old behaviour).
     *  6. Corroborated (2+ sources) at confidence >= 0.7 -> SAVE.
     *  7. Lone source at confidence >= 0.9 -> SAVE.
     *  8. Otherwise -> TRACK (the current behaviour for a lone
     *     0.7-confidence shot at priority 75-99).
     */
    fun decide(
        fused: DetectionFusionEngine.FusedShotHypothesis,
        anticipationPriority: Int
    ): SaveDecision {
        return try {
            decideUnchecked(fused, anticipationPriority)
        } catch (_: Throwable) {
            SaveDecision.TRACK
        }
    }

    private fun decideUnchecked(
        fused: DetectionFusionEngine.FusedShotHypothesis,
        anticipationPriority: Int
    ): SaveDecision {
        // Rule 1: invalid input -> TRACK (fail open).
        if (!fused.confidence.isFinite() || anticipationPriority < 0) return SaveDecision.TRACK
        if (fused.isShot && fused.sources.isEmpty()) return SaveDecision.TRACK
        // Rule 2: not a shot -> NO_SAVE.
        if (!fused.isShot) return SaveDecision.NO_SAVE
        // Rule 3: below the fusion strong bar -> TRACK. This is the
        // fresh-seed lone-gauge guard: a 0.5-confidence gauge hit
        // never saves on model evidence alone.
        if (fused.confidence < DetectionFusionEngine.STRONG_THRESHOLD) return SaveDecision.TRACK
        // Rule 4: below the QF-8 floor -> TRACK.
        if (anticipationPriority < SAVE_PRIORITY_FLOOR) return SaveDecision.TRACK
        // Rule 5: legacy bar, preserved exactly.
        if (anticipationPriority >= LEGACY_SAVE_PRIORITY) return SaveDecision.SAVE
        // Rule 6: corroborated shot saves at the strong bar.
        if (fused.sources.size >= 2) return SaveDecision.SAVE
        // Rule 7: lone source saves only when near-certain.
        if (fused.confidence >= LONE_SAVE_CONF) return SaveDecision.SAVE
        // Rule 8: otherwise TRACK (a lone 0.7-confidence shot at
        // priority 75-99 — the QF-8 band, unchanged without
        // corroboration or near-certainty).
        return SaveDecision.TRACK
    }
}
