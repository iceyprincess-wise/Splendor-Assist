package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong

/**
 * FightingSpiritEngine — player skill amplifier.
 *
 * WHAT IT MODELS:
 *   Fighting Spirit is an eFootball player skill that activates under
 *   pressure or adversity, giving a committed decisive boost to all
 *   actions. The engine does NOT generate its own gesture — it amplifies
 *   the best contribution already chosen by arbitration.
 *
 * WHEN IT FIRES:
 *   - defenderDensity > 0.55 (majority of visible players are opponents)
 *   - OR panic is active (emergency situation)
 *   - AND frame is trusted (vision has real data)
 *   - Cooldown: 800ms between activations (no spam amplification)
 *
 * EFFECTS WHEN ACTIVE:
 *   1. authorityBoost: multiplier [1.0 → 1.35] applied to the winning
 *      contribution's authority before execution.
 *   2. panicResistance: reduces the panic penalty from 50% to 25%
 *      (fighting spirit players commit under pressure, not retreat).
 *   3. durationBoost: adds [0 → 12ms] to the gesture duration hint,
 *      representing a committed, decisive contact.
 *
 * AMPLIFICATION CURVE:
 *   Scales with pressure: light pressure (0.55) → 1.10x boost.
 *   Full defensive wall (1.0) → 1.35x boost.
 *   Panic alone (no density data) → 1.20x flat boost.
 *
 * LOGGED TO:
 *   RuntimeLogger tag FIGHTING_SPIRIT — visible in Splendor_Field_Logs.txt
 */
object FightingSpiritEngine {

    private const val DENSITY_THRESHOLD = 0.55f
    private const val COOLDOWN_MS = 800L
    private const val MAX_BOOST = 1.35f
    private const val PANIC_BOOST = 1.20f

    private val activations = AtomicLong(0L)
    @Volatile private var lastActivationMs = 0L
    @Volatile private var lastBoost = 1.0f
    @Volatile private var active = false

    data class FightingSpiritResult(
        val active: Boolean,
        val authorityBoost: Float,   // multiply into authority
        val panicResistance: Float,  // 0.5 → 0.75 when active (less penalty)
        val durationBoostMs: Long    // add to durationHintMs
    )

    /**
     * Call once per frame AFTER arbitration has chosen a winner.
     * Returns a result the caller uses to scale the chosen contribution.
     */
    fun evaluate(frame: RuntimeFrame): FightingSpiritResult {
        if (!frame.trusted) {
            active = false
            return inert()
        }

        val now = System.currentTimeMillis()
        val cooldownOk = (now - lastActivationMs) >= COOLDOWN_MS

        val densityFires = frame.defenderDensity >= DENSITY_THRESHOLD
        val panicFires   = frame.panic

        if ((!densityFires && !panicFires) || !cooldownOk) {
            // Decay active state after one missed cycle
            if (now - lastActivationMs > COOLDOWN_MS * 2) active = false
            return inert()
        }

        // ── Compute boost magnitude ───────────────────────────────────────
        val boost = when {
            densityFires -> {
                // Scale linearly: density 0.55 → 1.10x, 1.0 → 1.35x
                val t = ((frame.defenderDensity - DENSITY_THRESHOLD) /
                        (1.0f - DENSITY_THRESHOLD)).coerceIn(0f, 1f)
                (1.10f + t * (MAX_BOOST - 1.10f)).coerceIn(1.0f, MAX_BOOST)
            }
            panicFires -> PANIC_BOOST
            else -> 1.0f
        }

        val durationBoost = when {
            boost >= 1.30f -> 12L
            boost >= 1.20f -> 8L
            boost >= 1.10f -> 4L
            else -> 0L
        }

        lastActivationMs = now
        lastBoost = boost
        active = true
        val count = activations.incrementAndGet()

        RuntimeLogger.log(
            "FIGHTING_SPIRIT ACTIVE: boost=%.2f density=%.2f panic=%b duration+%dms (activation #%d)".format(
                boost, frame.defenderDensity, panicFires, durationBoost, count
            ),
            "FIGHTING_SPIRIT"
        )

        return FightingSpiritResult(
            active          = true,
            authorityBoost  = boost,
            panicResistance = 0.75f,  // reduces panic penalty: 0.5 → 0.75
            durationBoostMs = durationBoost
        )
    }

    fun isActive(): Boolean = active

    fun diagnostics(): Map<String, Any> = mapOf(
        "active"            to active,
        "activations"       to activations.get(),
        "lastBoost"         to lastBoost,
        "lastActivationMs"  to lastActivationMs
    )

    private fun inert() = FightingSpiritResult(
        active          = false,
        authorityBoost  = 1.0f,
        panicResistance = 0.5f,
        durationBoostMs = 0L
    )

    fun reset() {
        activations.set(0L)
        lastActivationMs = 0L
        lastBoost = 1.0f
        active = false
    }
}
