package com.assistant

import android.accessibilityservice.AccessibilityService


























import com.assistant.diagnostic.RuntimeLogger



import com.assistant.execution.CentralExecutionBus

import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource


import com.assistant.runtime.*


























import kotlin.math.*




























class LatencyDefeatingInputEngine(
    private val service: AccessibilityService
) {
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
            startX = targetStartX,
            startY = targetStartY,
            endX = targetEndX,
            endY = targetEndY,
            duration = pingCompensatedDuration.coerceAtLeast(MIN_STROKE_DURATION_MS)
        )
        
    val accepted = CentralExecutionBus.submit(request)
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
