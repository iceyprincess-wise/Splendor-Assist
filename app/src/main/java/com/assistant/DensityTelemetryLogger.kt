package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import java.util.Locale

/**
 * L3 — Defender-Density Telemetry Producer (DensityTelemetryLogger).
 *
 * Gap (Q67-22): shot/cross decisions use a density value nobody could
 * verify — the field exists in 10 contributors but was never logged live.
 *
 * Contract: in = the density value at each dispatch decision; out = one
 * log line `density=<v> action=<a>`; caller =
 * RuntimeDecisionLoop.onFrame (the dispatch site, on every accepted
 * route); consumer = the log + the next log-proof round.
 *
 * Diagnostic producer, decision-neutral: it reads a value already
 * computed (frame.defenderDensity) and writes one log line; no decision
 * input changes. It logs only accepted dispatches (cooling bounds the
 * rate), not every frame.
 */
object DensityTelemetryLogger {

    fun logDensity(density: Float, actionName: String) {
        try {
            RuntimeLogger.log(
                "density=${String.format(Locale.US, "%.3f", density)} action=$actionName",
                "DENSITY"
            )
        } catch (_: Throwable) { }
    }
}
