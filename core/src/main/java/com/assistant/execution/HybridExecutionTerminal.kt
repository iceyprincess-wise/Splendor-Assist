package com.assistant.execution

object HybridExecutionTerminal {

    /*
     * Execution Bus Priority Scale:
     * - GOALKEEPER / INTERCEPTION = 100 (High ring)
     * - SMART_ASSIST = 90 (Med ring)
     * - STUTTER = 80 (Low ring)
     *
     * Note: Threat priority scores (0..150) are internal contributor decision weights
     * (no MAX_THREAT_PRIORITY constant exists in this module; the 0..150 scale is
     * documented only, not enforced here.)
     */
    fun priority(
        source: ExecutionSource
    ): Int {

        return when(source) {
            ExecutionSource.GOALKEEPER -> 100
            ExecutionSource.INTERCEPTION -> 100
            ExecutionSource.SMART_ASSIST -> 90
            ExecutionSource.STUTTER -> 80
        }
    }

    fun route(
        request: ExecutionRequest
    ): Boolean {
        return CentralExecutionBus.submit(request)
    }
}
