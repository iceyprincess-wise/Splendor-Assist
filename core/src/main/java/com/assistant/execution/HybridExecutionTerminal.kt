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

    fun route(
        request: ExecutionRequest
    ): Boolean {
        return CentralExecutionBus.submit(request)
    }
}
