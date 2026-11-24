package com.assistant

import android.view.accessibility.AccessibilityEvent

/**
 * A11Y-FLOW — accessibility capture watchdog (2026-11-24).
 *
 * The 2026-11-24 failure was invisible to every health signal in the
 * app: the service was bound (G1 true), the survival registry said
 * CONNECTED, the gate-recovery engine saw nothing to heal — while zero
 * touch-interaction events arrived all match. Every existing signal
 * measures BIND state, never EVENT FLOW.
 *
 * This watchdog closes that gap on the existing 5 s self-heal cycle:
 * (1) it reads the EFFECTIVE mask via getServiceInfo() and re-asserts
 * it with setServiceInfo() when the three required bits are missing
 * (the stale-mask heal — the touch fix only asserts once, at bind);
 * (2) it logs the event-flow ages once a minute, so a healthy day and
 * a starved day are both provable from the logs.
 *
 * It does NOT alarm on "no touch events" alone: no touch for minutes
 * is normal whenever Ed is not touching (menus, idle). The mask check
 * is the healing signal; the ages are the evidence. A future alarm
 * needs a second touch source (the knob reader, once pushed) to say
 * "vision sees a finger down while accessibility sees nothing".
 */
object AccessibilityEventFlowWatchdog {

    private const val HEALTH_LOG_INTERVAL_MS = 60_000L

    @Volatile private var lastHealthLogMs = 0L
    @Volatile private var lastHealLogMs = 0L

    fun check(nowMs: Long = System.currentTimeMillis()) {
        val service = SmartAssistAccessibilityEngine.globalInstance ?: return
        // (1) Effective-mask verification — the stale-mask detector.
        val required = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        val effective = try { service.serviceInfo } catch (_: Throwable) { null }
        val maskOk = effective != null && (effective.eventTypes and required) == required
        if (!maskOk) {
            // Re-assert the exact XML values (mirror of the touch fix's
            // onServiceConnected block). Only when wrong — a blind
            // re-assert every cycle would churn the system binding.
            try {
                val info = android.accessibilityservice.AccessibilityServiceInfo().apply {
                    eventTypes = required
                    feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_HAPTIC
                    notificationTimeout = 0L
                    flags = 0
                    canRetrieveWindowContent = true
                    canPerformGestures = true
                }
                service.setServiceInfo(info)
                if (nowMs - lastHealLogMs >= HEALTH_LOG_INTERVAL_MS) {
                    lastHealLogMs = nowMs
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "A11Y_MASK_HEAL maskOk=false reasserted eventTypes=$required (effective was ${effective?.eventTypes})",
                        "SMART_ASSIST"
                    )
                }
            } catch (_: Throwable) { }
        }
        // (2) Periodic flow evidence — once a minute, whatever the state.
        if (nowMs - lastHealthLogMs >= HEALTH_LOG_INTERVAL_MS) {
            lastHealthLogMs = nowMs
            val anyAge = if (SmartAssistAccessibilityEngine.lastAccessibilityEventMs > 0L)
                nowMs - SmartAssistAccessibilityEngine.lastAccessibilityEventMs else -1L
            val touchAge = if (SmartAssistAccessibilityEngine.lastTouchInteractionMs > 0L)
                nowMs - SmartAssistAccessibilityEngine.lastTouchInteractionMs else -1L
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "A11Y_FLOW maskOk=$maskOk anyEventAgeMs=$anyAge touchEventAgeMs=$touchAge",
                    "SMART_ASSIST"
                )
            } catch (_: Throwable) { }
        }
    }
}
