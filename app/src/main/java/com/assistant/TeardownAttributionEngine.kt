package com.assistant

import com.assistant.diagnostic.RuntimeLogger

/**
 * L4 — Teardown Attribution (TeardownAttributionEngine).
 *
 * Gap (Q-N2): the app could not distinguish a user teardown from a system
 * teardown — the bus stop initiator at 19:06:31 on 2026-11-18 is still
 * UNPROVEN. f58 added `caller=` at the shutdown sites, but the *external*
 * action (Stop tap / accessibility toggle) was still unlogged.
 *
 * Contract: in = UI events (Stop tap, accessibility toggle/interrupt) +
 * system events (service destroy), timestamped; out = a
 * `teardownCause=<user|system|…>` log line correlated with shutdown();
 * caller = MainActivity (Stop tap) / OverlayService (onDestroy) /
 * SmartAssistAccessibilityEngine (onUnbind/onInterrupt/onDestroy);
 * consumer = the heal log + the next log-proof round.
 *
 * Diagnostic producer: it only records and logs; it never changes a
 * shutdown. Cause resolution: the most recent external event within the
 * 10 s correlation window wins (user-stop first, then accessibility-off,
 * then service-destroy, then interrupt); anything older — or no event at
 * all — reads `unknown`, never a guessed cause.
 *
 * Do NOT close Q-N2 on this: this engine only makes the NEXT teardown
 * attributable. The 19:06:31 initiator stays UNPROVEN until a session
 * logs it.
 */
object TeardownAttributionEngine {

    /** Correlation window: an external event older than this cannot have caused this shutdown. */
    const val CORRELATION_WINDOW_MS = 10_000L

    @Volatile private var lastUserStopMs: Long = 0L
    @Volatile private var lastUserStopSource: String = ""
    @Volatile private var lastAccessibilityOffMs: Long = 0L
    @Volatile private var lastAccessibilityOffSource: String = ""
    @Volatile private var lastServiceDestroyMs: Long = 0L
    @Volatile private var lastInterruptMs: Long = 0L

    /** MainActivity Stop Engine tap (user teardown). */
    fun noteUserStop(source: String) {
        lastUserStopMs = System.currentTimeMillis()
        lastUserStopSource = source
    }

    /** Accessibility service going away (unbind/destroy) or being toggled off. */
    fun noteAccessibilityOff(source: String) {
        lastAccessibilityOffMs = System.currentTimeMillis()
        lastAccessibilityOffSource = source
    }

    /** OverlayService.onDestroy (system/service teardown). */
    fun noteServiceDestroy() {
        lastServiceDestroyMs = System.currentTimeMillis()
    }

    /** SmartAssistAccessibilityEngine.onInterrupt (system interrupt). */
    fun noteInterrupt() {
        lastInterruptMs = System.currentTimeMillis()
    }

    /**
     * Resolves the cause for a shutdown happening now and logs the
     * correlation line. Called from RuntimeCoordinator.shutdown().
     * Returns the cause string for the caller (log-only use).
     */
    fun attributeShutdown(caller: String, nowMs: Long = System.currentTimeMillis()): String {
        val userStopAge = nowMs - lastUserStopMs
        val accessibilityAge = nowMs - lastAccessibilityOffMs
        val destroyAge = nowMs - lastServiceDestroyMs
        val interruptAge = nowMs - lastInterruptMs
        val (cause, lastEvent, ageMs) = when {
            lastUserStopMs > 0L && userStopAge in 0L..CORRELATION_WINDOW_MS ->
                Triple("user-stop", lastUserStopSource, userStopAge)
            lastAccessibilityOffMs > 0L && accessibilityAge in 0L..CORRELATION_WINDOW_MS ->
                Triple("accessibility-off", lastAccessibilityOffSource, accessibilityAge)
            lastServiceDestroyMs > 0L && destroyAge in 0L..CORRELATION_WINDOW_MS ->
                Triple("system-service-destroy", "OverlayService.onDestroy", destroyAge)
            lastInterruptMs > 0L && interruptAge in 0L..CORRELATION_WINDOW_MS ->
                Triple("system-interrupt", "SmartAssistAccessibilityEngine.onInterrupt", interruptAge)
            else -> Triple("unknown", "none", -1L)
        }
        try {
            RuntimeLogger.log(
                "TEARDOWN cause=$cause caller=$caller lastUserEvent=$lastEvent ageMs=$ageMs",
                "RUNTIME"
            )
        } catch (_: Throwable) { }
        return cause
    }

    fun reset() {
        lastUserStopMs = 0L
        lastUserStopSource = ""
        lastAccessibilityOffMs = 0L
        lastAccessibilityOffSource = ""
        lastServiceDestroyMs = 0L
        lastInterruptMs = 0L
    }
}
