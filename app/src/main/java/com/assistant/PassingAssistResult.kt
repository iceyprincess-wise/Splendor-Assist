package com.assistant

/**
 * Corrected pass target plus the interception risk of aiming there.
 *
 * No68-A-D4: `interceptionRisk` has had different meanings across the
 * producers in TrueTargetPassingEngine, and callers must not mix them
 * up: `optimize()` reports `1 - retention` (a geometry fraction, 0 at
 * full retention); `optimizeWithAdvancedTactics()` reports
 * `defenderDensity * 0.7`. (`optimizeWithRunPrediction()` reported 0
 * always — it had no defender input — and is deleted as dead in
 * No68-A-D13.) TruePassContributor only compares the value within one
 * producer's branch, so the inconsistency is contained — but no caller
 * may compare risks across producers.
 *
 * No68-A-D4 / Q67-9 CLOSED: `requiresEscapeVector` is deleted. It was
 * set (only) when `defenderDensity > 0.8` and read by NOBODY in the
 * whole repo (full-tree grep); wiring it would need a new escape-vector
 * dispatch design that the trace does not define, and no non-speculative
 * consumer exists. The pressured-pass signal itself survives as
 * `interceptionRisk = defenderDensity * 0.7`, which TruePass already
 * folds into authority.
 */
data class PassingAssistResult(
    val correctedX: Float,
    val correctedY: Float,
    val interceptionRisk: Float
)

