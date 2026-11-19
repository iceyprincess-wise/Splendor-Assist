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
     * injector, the OCR pipe), so one log line here covers all 43
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
        // N5: clamp every request to the LIVE capture bounds before
        // submit, so the [Dispatch] line shows the clamped values and no
        // off-screen endpoint is ever accepted (4 accepted dispatches
        // with to.y 647-821 in a 640-tall capture, 2026-11-18 session).
        // Clamp, do not drop: a clamped endpoint still executes as an
        // edge swipe (the H-D5 physical-clamp behaviour, made visible).
        val bounded = clampToCapture(request)
        val accepted = CentralExecutionBus.submit(bounded)
        try {
            com.assistant.diagnostic.RuntimeLogger.log(dispatchLine(bounded, accepted), "Dispatch")
        } catch (_: Throwable) {
            // A logging failure must never fail a dispatch.
        }
        return accepted
    }

    // N5: first-clamp witness, once per session (later clamps are silent;
    // the [Dispatch] line shows the clamped values).
    @Volatile private var loggedClamp = false

    // N5: pure, pinned in the f58 harness. Clamps start/end to
    // [0, captureWidth] x [0, captureHeight] from DispatchBounds (live
    // bounds published by OverlayService on every recreate; 0x0 means
    // capture not yet established — pass through unchanged, never invent
    // bounds). Non-finite coordinates pass through untouched so
    // CentralExecutionBus.requestIsValid still rejects them as before.
    internal fun clampToCapture(request: ExecutionRequest): ExecutionRequest {
        val w = DispatchBounds.captureWidth
        val h = DispatchBounds.captureHeight
        if (w <= 0f || h <= 0f) return request
        if (!request.startX.isFinite() || !request.startY.isFinite() ||
            !request.endX.isFinite() || !request.endY.isFinite()) return request
        if (request.startX in 0f..w && request.startY in 0f..h &&
            request.endX in 0f..w && request.endY in 0f..h) return request
        if (!loggedClamp) {
            loggedClamp = true
            try { com.assistant.diagnostic.RuntimeLogger.log("CLAMPED_DISPATCH actionId=${request.actionId} origTo=(${request.endX},${request.endY}) clampedTo=(${request.endX.coerceIn(0f, w)},${request.endY.coerceIn(0f, h)}) bounds=${w.toInt()}x${h.toInt()}", "Dispatch") } catch (_: Throwable) {}
        }
        return request.copy(
            startX = request.startX.coerceIn(0f, w),
            startY = request.startY.coerceIn(0f, h),
            endX = request.endX.coerceIn(0f, w),
            endY = request.endY.coerceIn(0f, h)
        )
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
