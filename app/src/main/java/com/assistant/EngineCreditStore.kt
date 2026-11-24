package com.assistant

import android.content.Context
import android.content.SharedPreferences

/**
 * PerEngineCredit — per-engine outcome memory + persistence.
 *
 * OutcomePolicy reliability is per-ActionClass only, so arbitration could
 * not learn which engines actually work on this device: a good engine and
 * a bad engine in the same ActionClass were treated identically. This store
 * tracks each engine's observed success/failure (from ActionOutcomeVerifier,
 * the same macro-state verdict that drives per-class reliability) and
 * maintains a credit score per engine name.
 *
 * The credit feeds arbitration through RuntimeDecisionLoop, which multiplies
 * each contribution's confidence by credit(engine) — the same site and the
 * same pattern as the per-class confidenceFactor. A credit of 1.0 is neutral
 * (no change); a lower credit down-weights the engine's score.
 *
 * Learning rate mirrors OutcomePolicy exactly (+0.02 success, -0.05 failure,
 * floor 0.6, cap 1.0) so per-engine credit moves at the same rate as the
 * per-class reliability it sits beside. The 0.6 floor means an engine is
 * never fully disabled — it can always recover with successes.
 *
 * Decay: a credit drifts back toward 1.0 (neutral) when the engine has not
 * been observed for a while, so a stale bad score cannot permanently bury
 * an engine. The drift is computed on read from the stored last-update time;
 * the stored value only changes on a recorded outcome.
 *
 * Persistence follows OverrideStore's pattern: a nullable SharedPreferences
 * set by initialize() from the application context, fail-safe (credit 1.0,
 * in-memory-only recording) when never initialized. Learned credit survives
 * restarts and verifier resets — that is the point of the store.
 */
object EngineCreditStore {
    private const val PREFS = "splendor_engine_credit"
    private const val CREDIT_PREFIX = "credit:"
    private const val UPDATED_PREFIX = "updated:"

    // Mirror OutcomePolicy's learning rate and bounds exactly.
    private const val CREDIT_FLOOR = 0.6f
    private const val CREDIT_CAP = 1.0f
    private const val SUCCESS_DELTA = 0.02f
    private const val FAILURE_DELTA = -0.05f

    // Idle decay: no drift for the first minute without an observation,
    // then a linear drift back to neutral over the next 5 minutes.
    private const val DECAY_AFTER_MS = 60_000L
    private const val DECAY_WINDOW_MS = 300_000L

    @Volatile private var prefs: SharedPreferences? = null
    private val credit = mutableMapOf<String, Float>()
    private val lastUpdatedMs = mutableMapOf<String, Long>()

    @JvmStatic fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return
        try {
            for ((key, value) in p.all) {
                if (key.startsWith(CREDIT_PREFIX) && value is Float) {
                    credit[key.removePrefix(CREDIT_PREFIX)] = value.coerceIn(CREDIT_FLOOR, CREDIT_CAP)
                } else if (key.startsWith(UPDATED_PREFIX) && value is Long) {
                    lastUpdatedMs[key.removePrefix(UPDATED_PREFIX)] = value
                }
            }
        } catch (_: Throwable) { }
    }

    private fun persist(engine: String) {
        val p = prefs ?: return
        try {
            val c = credit[engine] ?: return
            val updated = lastUpdatedMs[engine] ?: return
            p.edit()
                .putFloat(CREDIT_PREFIX + engine, c)
                .putLong(UPDATED_PREFIX + engine, updated)
                .apply()
        } catch (_: Throwable) { }
    }

    /**
     * Effective credit for an engine: 1.0 when the engine is blank, unknown,
     * or the store was never initialized. A stored credit drifts toward 1.0
     * after DECAY_AFTER_MS idle, reaching neutral after DECAY_WINDOW_MS.
     */
    @Synchronized fun credit(engine: String): Float {
        if (engine.isBlank()) return 1f
        val stored = credit[engine] ?: return 1f
        val updated = lastUpdatedMs[engine] ?: return stored.coerceIn(CREDIT_FLOOR, CREDIT_CAP)
        val idleMs = System.currentTimeMillis() - updated
        if (idleMs <= DECAY_AFTER_MS) return stored.coerceIn(CREDIT_FLOOR, CREDIT_CAP)
        val drift = ((idleMs - DECAY_AFTER_MS).toFloat() / DECAY_WINDOW_MS).coerceIn(0f, 1f)
        return (stored + (1f - stored) * drift).coerceIn(CREDIT_FLOOR, CREDIT_CAP)
    }

    /**
     * Record one judged outcome for an engine. Only called with the verdict
     * ActionOutcomeVerifier already observed (the policy macro verdict, the
     * same signal that updates per-class reliability). Blank engine names
     * are ignored — "" means "no engine claimed it", never a guessed name.
     */
    @Synchronized fun recordOutcome(engine: String, success: Boolean) {
        if (engine.isBlank()) return
        val current = credit[engine] ?: 1f
        val delta = if (success) SUCCESS_DELTA else FAILURE_DELTA
        credit[engine] = (current + delta).coerceIn(CREDIT_FLOOR, CREDIT_CAP)
        lastUpdatedMs[engine] = System.currentTimeMillis()
        persist(engine)
    }

    @Synchronized fun diagnostics(): Map<String, Map<String, Float>> {
        return credit.keys.associateWith { engine ->
            mapOf("effective" to credit(engine), "stored" to (credit[engine] ?: 1f))
        }
    }
}
