package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

// Phase L (L-9): the service constructor parameter is gone —
// the class body never read it (the swipe routes through
// HybridExecutionTerminal); the only construction site passes nothing now.
class LatencyDefeatingInputEngine {
    private companion object {
        const val MIN_STROKE_DURATION_MS = 1L
        // Phase 100 is a dedicated zero-latency reservation key, NOT an
        // ActionClass ordinal (those are 0-7). The phase namespace is
        // ad-hoc across the repo; 100 only has to be distinct, and the bus
        // validity check (phase >= 0) accepts it.
        const val ZERO_LATENCY_PHASE = 100
    }

fun injectZeroLatencySwipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    restrictedDuration: Long
): Boolean {
    val targetStartX = startX.coerceAtLeast(0f)
    val targetStartY = startY.coerceAtLeast(0f)
    val targetEndX = endX.coerceAtLeast(0f)
    val targetEndY = endY.coerceAtLeast(0f)

    // Live input latency, not an assumed constant. InputLatencyEngine
    // measures main-thread dispatch latency continuously; it is 0 until
    // the first measurement lands, so the declared fallback is kept only
    // for that window. The value extends the stroke so a congested device
    // does not drop the swipe inside a server validation frame.
    // Local dispatch latency only. Zero means unknown, so do not compensate.
    val localDispatchLatencyMs = InputLatencyEngine.latencyMs.coerceIn(0L, 250L)
    val pingCompensatedDuration = if (localDispatchLatencyMs > 0L)
        restrictedDuration + (localDispatchLatencyMs / 4L).coerceAtMost(25L) else restrictedDuration

    val request = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = ZERO_LATENCY_PHASE, 
            // no59 ITEM 1: carry the engine name to the dispatch log.
            engine = "LatencyDefeatingInputEngine",
            startX = targetStartX,
            startY = targetStartY,
            endX = targetEndX,
            endY = targetEndY,
            duration = pingCompensatedDuration.coerceAtLeast(MIN_STROKE_DURATION_MS)
        )
        
    // D-23-03: route through the terminal (single entry point); route()
    // is a pure passthrough to CentralExecutionBus.submit, so this is
    // behaviour-identical.
    val accepted = HybridExecutionTerminal.route(request)
    // G2 (item 20): timing-feedback submit leg — only when the bus accepts.
    if (accepted) GestureTimingFeedbackEngine.recordSubmission(request.actionId)
    if (!accepted) RuntimeLogger.log("injectZeroLatencySwipe rejected by bus, swipe lost", "LatencyDefeatingInputEngine")
    return accepted
    }
}

/* ========
MemoryStabilityOptimizer
======== */
/**
 * HIGH-PERFORMANCE MEMORY STABILIZER
 * Engineered to support 60Hz/120Hz micro-gesture frameworks.
 * Prevents Main-Thread GC (Garbage Collection) pauses that would otherwise drop 
 * frame synchronization or interrupt Server-Tick bounds.
 */
