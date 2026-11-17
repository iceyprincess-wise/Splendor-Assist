package com.assistant.execution

object HybridExecutionTerminal {

    /*
     * Execution Bus Priority Scale (single home for these numbers):
     * - GOALKEEPER / INTERCEPTION = PRIORITY_HIGH (100, High ring)
     * - SMART_ASSIST = PRIORITY_MEDIUM (90, Med ring)
     * - STUTTER = PRIORITY_LOW (80, Low ring)
     * The bus orders by ring, not by these numbers; priority() is only
     * used to compare sources (drain arbitration, preemption).
     *
     * Note: these 80/90/100 values are NOT threat priority scores.
     * Threat priority is a separate, open-ended sum (base score +
     * distance/zone bonuses + slider and adaptive bonuses); 150 is the
     * PANIC threshold (PanicSaveEngine, OneVsOnePanicEngine,
     * ShotAnticipationEngine, NativePorts.panic) and the authority
     * normalisation divisor (NativePorts.keeper, CrossClaimContributor,
     * PanicSaveContributor: priority / 150f, clamped to 0..1) - not the
     * top of the threat scale. Threat priority can exceed 150 (the SAVE
     * band is [100,150), PANIC is >= 150). No MAX_THREAT_PRIORITY
     * constant exists; do not clamp a bus priority to 150.
     */
    // Bus priority levels. Single home for the 80/90/100 scale; the bus
    // comment in CentralExecutionBus restates it - keep it pointing here.
    const val PRIORITY_HIGH = 100
    const val PRIORITY_MEDIUM = 90
    const val PRIORITY_LOW = 80

    fun priority(
        source: ExecutionSource
    ): Int {

        return when(source) {
            ExecutionSource.GOALKEEPER -> PRIORITY_HIGH
            ExecutionSource.INTERCEPTION -> PRIORITY_HIGH
            ExecutionSource.SMART_ASSIST -> PRIORITY_MEDIUM
            ExecutionSource.STUTTER -> PRIORITY_LOW
        }
    }

    /*
     * no59 ITEM 1 — PER-ENGINE DISPATCH LOGGING. route() is the single
     * choke point every dispatch flows through (RuntimeDecisionLoop
     * onFrame, the ContributionRegistry emergency lane, the legacy
     * injectWinningVector, BurstForensics re-asserts, the zero-latency
     * injector, the OCR pipe), so one log line here covers all 44
     * engines at once — no per-engine edits. Each line answers, for
     * that dispatch: WHICH engine (request.engine, "?" when none claimed
     * it), WHAT was dispatched (actionClass + phase + start/end slot
     * coordinates + duration), and WHETHER the bus took it (accepted).
     * The line is built by dispatchLine() (pure, pinned in the no59
     * harness) and written through RuntimeLogger, so it lands in
     * Splendor_Field_Logs.txt in the item-2 grouped format and in the
     * LogMirror. Lock order is safe: route() is called under the
     * ContributionRegistry monitor from offer(), and RuntimeLogger never
     * takes that monitor, so no cycle.
     */
    fun route(
        request: ExecutionRequest
    ): Boolean {
        val accepted = CentralExecutionBus.submit(request)
        try {
            com.assistant.diagnostic.RuntimeLogger.log(dispatchLine(request, accepted), "Dispatch")
        } catch (_: Throwable) {
            // A logging failure must never fail a dispatch.
        }
        return accepted
    }

    /**
     * One dispatch line. Pure (no Android, no I/O) so the harness pins
     * it exactly. Coordinates are truncated to integers: the log answers
     * "which slot/target", it is not a replay trace. An unclaimed engine
     * prints "?" — never a guessed name.
     */
    fun dispatchLine(request: ExecutionRequest, accepted: Boolean): String {
        val engine = request.engine.ifBlank { "?" }
        val action = request.actionClass?.name ?: "?"
        return "engine=$engine source=${request.source} action=$action phase=${request.phase} " +
            "from=(${request.startX.toInt()},${request.startY.toInt()}) " +
            "to=(${request.endX.toInt()},${request.endY.toInt()}) " +
            "dur=${request.duration}ms accepted=$accepted id=${request.actionId}"
    }
}
