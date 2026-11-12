package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object ForwardRunOpportunityEngine {

    fun evaluate(
        distance:Float,
        strength:Int
    ):ForwardRunResult {

        val factor=
            strength.coerceIn(0,100)/100f

        return ForwardRunResult(
            runBoost=
                1f + (factor * 0.80f),

            laneScore=
                (distance/1000f)
                    .coerceAtMost(1f),

            confidence=
                (0.65f + factor*0.35f)
                    .coerceAtMost(1f)
        )
    }
}

/* ========
FrameAssembler
======== */
/*
 * Reads the state stores ONCE per capture and produces one immutable frame.
 * Engines must never touch these stores directly after this exists — they
 * receive this frame instead. Guarded per-store so one missing store cannot
 * abort frame assembly.
 *
 * REPAIRED (Task B): hasBall was derived from raw stored coordinates, so
 * once a ball had EVER been seen the flag stayed true forever. hasBall now
 * requires the sighting to be FRESH (VisionTrust age-decay window).
 *
 * REPAIRED (Task C round 1): frame confidence was crossing-lane-only,
 * zeroing the whole contributor stack in any phase without a viable
 * crossing lane. Confidence now follows fresh ball trust.
 *
 * REPAIRED (Task C round 2 - FIELD-LOG PROVEN): hasBall meant "ball is
 * VISIBLE", not "WE possess it". The 18:38 field session shows the result:
 * hasBall=true for essentially the whole match, so every contributor gated
 * on !hasBall - ThreatPriority, CrossClaim, KeeperBias, PanicSave,
 * BallPress, and with them tackling/interception behaviour - recorded
 * ZERO contributions all session while the attack-side engines ran 6574
 * cycles. The keeper wasn't dying; it was never allowed to speak because
 * "ball on screen" was read as "we have it".
 *
 * Fix: hasBall now follows the REAL possession verdict from
 * BallPossessionEngine (via Phase3WorldState) whenever that verdict has
 * usable confidence; the fresh-sighting rule remains as fallback only when
 * possession has no data yet (cold start). Admin-tunable floor:
 *   assist.possession.min_conf (default 0.20)
 */
