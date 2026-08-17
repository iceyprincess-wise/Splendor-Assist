package com.assistant.adapter.input

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * GestureTimingFeedbackEngine — actual gesture dispatch latency tracker.
 *
 * InputLatencyEngine measures main-thread scheduling delay — a proxy for
 * how congested the app is. It does NOT measure gesture dispatch latency:
 * the time from CentralExecutionBus.submit() to the gesture actually
 * executing in the accessibility service.
 *
 * This engine provides a cooperative timing hook:
 * - recordSubmission(sequenceId): called at bus submit time
 * - recordDispatch(sequenceId): called at gesture dispatch time
 * - computes round-trip latency per gesture
 * - publishes a running EWMA of gesture dispatch latency to the bus
 *
 * Because gesture dispatch happens in SmartAssistAccessibilityEngine
 * (a different class, same process), the hook is static and the
 * accessibility engine calls recordDispatch() at gesture execution start.
 *
 * If no dispatch is recorded within TIMEOUT_MS, the gesture is counted
 * as expired (bus staleness) — a direct measure of queue pressure.
 */
object GestureTimingFeedbackEngine {

    private const val TIMEOUT_MS = 350L   // matches SMART_ASSIST bus lifetime 300ms + margin
    private const val EWMA_ALPHA = 0.2f
    private const val LOG_EVERY_N = 50    // log every 50 gestures

    @Volatile var avgDispatchMs = 0f; private set
    @Volatile var expiredCount = 0L; private set
    @Volatile var measuredCount = 0L; private set
    @Volatile var lastDispatchMs = 0L; private set

    private val pendingSubmitMs = AtomicLong(-1L)
    private val pendingSeq = AtomicLong(-1L)
    private val globalSeq = AtomicLong(0L)

    /** Called at CentralExecutionBus.submit() time for SMART_ASSIST gestures. */
    fun recordSubmission(): Long {
        if (AdapterSignalBus.manualPerformanceEscalation) {
            RuntimeLogger.log(
                "GestureTiming: manual performance escalation active",
                "INPUT"
            )
        }
        val seq = globalSeq.incrementAndGet()
        pendingSeq.set(seq)
        pendingSubmitMs.set(System.currentTimeMillis())
        return seq
    }

    /**
     * Called at gesture execution start in SmartAssistAccessibilityEngine.
     * seq must match the pending sequence to count.
     */
    fun recordDispatch(seq: Long) {
        val submitMs = pendingSubmitMs.get()
        if (submitMs < 0 || pendingSeq.get() != seq) return
        val elapsed = System.currentTimeMillis() - submitMs
        pendingSubmitMs.set(-1L)
        val clampedMs = elapsed.coerceIn(0L, 5000L).toFloat()
        avgDispatchMs = if (avgDispatchMs == 0f) clampedMs
                        else avgDispatchMs * (1 - EWMA_ALPHA) + clampedMs * EWMA_ALPHA
        lastDispatchMs = elapsed
        measuredCount++

        // Publish to bus as the authoritative gesture latency signal
        val cls = when {
            avgDispatchMs < 33f  -> "GESTURE_FAST"
            avgDispatchMs < 80f  -> "GESTURE_OK"
            avgDispatchMs < 150f -> "GESTURE_SLOW"
            else                 -> "GESTURE_LAGGING"
        }
        AdapterSignalBus.publishInput(cls, avgDispatchMs.toLong())

        if (measuredCount % LOG_EVERY_N == 0L) {
            RuntimeLogger.log(
                "GestureDispatch avg=${String.format("%.1f", avgDispatchMs)}ms " +
                    "last=${elapsed}ms expired=$expiredCount measured=$measuredCount",
                "INPUT"
            )
        }
    }

    /**
     * Called periodically to detect gestures that were submitted but
     * never dispatched (bus staleness / queue expiry).
     */
    fun checkExpiry() {
        val submitMs = pendingSubmitMs.get()
        if (submitMs < 0) return
        if (System.currentTimeMillis() - submitMs > TIMEOUT_MS) {
            expiredCount++
            pendingSubmitMs.set(-1L)
            RuntimeLogger.log(
                "GestureDispatch EXPIRED after ${TIMEOUT_MS}ms — bus queue pressure " +
                    "(total expired=$expiredCount)",
                "INPUT"
            )
        }
    }

    fun reset() {
        avgDispatchMs = 0f; expiredCount = 0L; measuredCount = 0L
        lastDispatchMs = 0L; pendingSubmitMs.set(-1L); pendingSeq.set(-1L)
    }
}
