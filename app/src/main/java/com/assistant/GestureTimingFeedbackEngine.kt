package com.assistant

import android.os.Process

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import java.util.Locale

object GestureTimingFeedbackEngine {

    // 120ms is ~2 frames at 15fps. Anything older is unusable in fast-paced eFootball.
    private const val TIMEOUT_MS = 120L
    private const val EWMA_ALPHA = 0.4f
    private const val LOG_EVERY_N = 50
    // G2 (item 20): pending submissions are keyed by the request actionId —
    // the one identifier that travels with the request through
    // CentralExecutionBus (its ring holds up to 1024 entries and copy()
    // in consumeFrom() preserves actionId). The old single-slot seq design
    // could not survive a second submit before the first dispatch, and no
    // seq field exists on ExecutionRequest to carry one. Cap mirrors
    // ActionOutcomeVerifier's pending map (64, oldest evicted).
    private const val MAX_PENDING = 64

    @Volatile var avgDispatchMs = 0f; private set
    @Volatile var expiredCount = 0L; private set
    @Volatile var measuredCount = 0L; private set
    @Volatile var lastDispatchMs = 0L; private set

    // G2: actionId -> submit wall-clock ms. @Synchronized: submitters run on
    // the decision/OCR/zero-latency threads while the bus thread dispatches
    // and expires; the old single AtomicLong slot was also racy across
    // concurrent submits (a second submit overwrote the first's timestamp).
    private val pendingSubmitMs = LinkedHashMap<String, Long>()

    /**
     * Called at every app-module submit site, ONLY when the bus accepts the
     * request: RuntimeDecisionLoop, LatencyDefeatingInputEngine,
     * ActiveGestureController (legacy injectWinningVector) and the OverlayService
     * OCR pipe — each right after HybridExecutionTerminal.route() returns true.
     * The route() choke point itself lives in :core and cannot call this
     * app-module engine (app depends on :core, never the reverse), so the
     * four app call sites are the channel; the :core emergency lane
     * (ContributionRegistry) and BurstForensics re-asserts submit without a
     * record and their dispatches below are a deliberate no-op, never a
     * fabricated sample.
     */
    @Synchronized
    fun recordSubmission(actionId: String) {
        if (pendingSubmitMs.size >= MAX_PENDING) {
            pendingSubmitMs.remove(pendingSubmitMs.keys.first())
        }
        pendingSubmitMs[actionId] = System.currentTimeMillis()
    }

    /**
     * Called at gesture execution start in SmartAssistAccessibilityEngine:
     * the busRunnable, right after CentralExecutionBus.consume() returns a
     * request. That consume site covers every dispatch path (RDL, keeper
     * emergency, AGC legacy, OCR pipe, zero-latency), so each measured
     * gesture records exactly once. An actionId with no submission (core
     * emergency lane / BurstForensics re-assert) returns without measuring.
     */
    @Synchronized
    fun recordDispatch(actionId: String) {
        val submitMs = pendingSubmitMs.remove(actionId) ?: return
        val elapsed = System.currentTimeMillis() - submitMs
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
            else                 -> "LAGGING"
        }

        // G2: the token published at/over 2 frames (120ms) is the bare
        // "LAGGING" string. The old prefixed tokens had ZERO readers
        // anywhere, and the old emergency override shadowed the lagging
        // branch entirely, so no consumer ever fired. "LAGGING" is the
        // exact token the live decision consumers match:
        // SpeedCompensationContributor shortens gestures 0.7x while it holds
        // and LagVerdictEngine counts it as starved input. Below 120ms the
        // FAST/OK/SLOW token is published as before (control-room display +
        // crash dump read the raw string), which also clears "LAGGING" on
        // recovery — last-writer-wins is this bus's known semantics.
        AdapterSignalBus.publishInput(cls, avgDispatchMs.toLong())

        if (measuredCount % LOG_EVERY_N == 0L) {
            RuntimeLogger.log(
                "GestureDispatch avg=${String.format(Locale.US, "%.1f", avgDispatchMs)}ms " +
                    "last=${elapsed}ms expired=$expiredCount measured=$measuredCount",
                "INPUT"
            )
        }
    }

    /**
     * Called periodically to detect gestures that were submitted but
     * never dispatched (bus staleness / queue expiry). Wired once per bus
     * poll (9ms) in SmartAssistAccessibilityEngine's busRunnable, just
     * before CentralExecutionBus.consume().
     */
    @Synchronized
    fun checkExpiry() {
        val now = System.currentTimeMillis()
        val expired = pendingSubmitMs.entries
            .filter { now - it.value > TIMEOUT_MS }
            .map { it.key }
        for (actionId in expired) {
            val submitMs = pendingSubmitMs.remove(actionId) ?: continue
            expiredCount++
            // publishInput latency abuse (batch 5): the old code passed
            // expiredCount (a count) as latencyMs. The real latency is
            // the age of the expired submission at expiry.
            val lateMs = now - submitMs

            // HARDWORKING ELIMINATOR: Force boost main thread when queue is stale
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}

            // Signal the CentralExecutionBus to drop stale events
            AdapterSignalBus.publishInput("BUS_STALE_PURGE", lateMs)

            RuntimeLogger.log(
                "GestureDispatch EXPIRED after ${TIMEOUT_MS}ms — bus queue pressure " +
                    "(total expired=$expiredCount). PURGE & BOOST triggered.",
                "INPUT"
            )
        }
    }

    @Synchronized
    fun reset() {
        avgDispatchMs = 0f; expiredCount = 0L; measuredCount = 0L
        lastDispatchMs = 0L; pendingSubmitMs.clear()
    }
}


/* ========
InputLatencyEngine
======== */
