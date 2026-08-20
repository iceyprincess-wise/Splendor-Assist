package com.assistant.adapter.input

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * GestureTimingFeedbackEngine — actual gesture dispatch latency tracker & ELIMINATOR.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * 15fps = 66.6ms/frame. Gestures must execute within 1-2 frames to prevent input lag.
 *
 * This engine acts as a HARDWORKING ELIMINATOR:
 * - Conditionlessly boosts thread priority if dispatch exceeds 1 frame (66ms).
 * - Triggers BUS_STALE_PURGE if queue congestion exceeds 2 frames (120ms).
 * - Uses higher EWMA alpha for rapid reaction to sudden lag spikes.
 */
object GestureTimingFeedbackEngine {

    // 120ms is ~2 frames at 15fps. Anything older is unusable in fast-paced eFootball.
    private const val TIMEOUT_MS = 120L   
    private const val EWMA_ALPHA = 0.4f
    private const val LOG_EVERY_N = 50

    @Volatile var avgDispatchMs = 0f; private set
    @Volatile var expiredCount = 0L; private set
    @Volatile var measuredCount = 0L; private set
    @Volatile var lastDispatchMs = 0L; private set

    private val pendingSubmitMs = AtomicLong(-1L)
    private val pendingSeq = AtomicLong(-1L)
    private val globalSeq = AtomicLong(0L)

    /** Called at CentralExecutionBus.submit() time for SMART_ASSIST gestures. */
    fun recordSubmission(): Long {
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

        // CONDITIONLESS ACTIVE MITIGATION:
        // If a single gesture takes longer than 1 frame at 15fps (66ms),
        // immediately boost the current thread priority to prevent starvation
        // of subsequent gestures in the fast-paced eFootball environment.
        if (elapsed > 66L) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
        }

        val cls = when {
            avgDispatchMs < 33f  -> "GESTURE_FAST"
            avgDispatchMs < 66f  -> "GESTURE_OK"
            avgDispatchMs < 120f -> "GESTURE_SLOW"
            else                 -> "GESTURE_LAGGING"
        }
        
        // Publish emergency signal if average latency exceeds 2 frames
        val signal = if (avgDispatchMs >= 120f) "GESTURE_EMERGENCY" else cls
        AdapterSignalBus.publishInput(signal, avgDispatchMs.toLong())

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
            
            // HARDWORKING ELIMINATOR: Force boost main thread when queue is stale
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
            
            // Signal the CentralExecutionBus to drop stale events
            AdapterSignalBus.publishInput("BUS_STALE_PURGE", expiredCount)
            
            RuntimeLogger.log(
                "GestureDispatch EXPIRED after ${TIMEOUT_MS}ms — bus queue pressure " +
                    "(total expired=$expiredCount). PURGE & BOOST triggered.",
                "INPUT"
            )
        }
    }

    fun reset() {
        avgDispatchMs = 0f; expiredCount = 0L; measuredCount = 0L
        lastDispatchMs = 0L; pendingSubmitMs.set(-1L); pendingSeq.set(-1L)
    }
}
