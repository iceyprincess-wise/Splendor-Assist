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
 * TOUCH-FALLBACK (2026-11-25): the heal also covers the
 * FLAG_SEND_MOTION_EVENTS bit — the flag rides along in every re-assert,
 * and a missing flag alone (mask fine, flag stripped) now triggers the
 * heal too. A11Y_FLOW gains motionEventAgeMs so the two touch channels
 * stay separately provable: touchEventAgeMs is the last touch evidence
 * from EITHER channel (the MotionEvent path stamps it), motionEventAgeMs
 * is the MotionEvent channel alone.
 * FLAG-RETURN (2026-11-26, #74): flag heal restored after v283 proved
 * silent capture recovery — monitored test.
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
        // TOUCH-FALLBACK (2026-11-25): the MotionEvents flag is part of the
        // healthy state too — heal when it is missing even if the mask is
        // fine, so a stripped flag can never silently outlive the mask.
        val flagOk = effective != null &&
            (effective.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS) != 0
        if (!maskOk || !flagOk) {
            // Re-assert the exact XML values (mirror of the touch fix's
            // onServiceConnected block). Only when wrong — a blind
            // re-assert every cycle would churn the system binding.
            // CI #64-#68 fix: same as the touch fix — the two
            // capability assignments did not compile (getter-only val /
            // unresolved; javap, API 34/36) and capabilities live only
            // in the XML config. Reuse the EFFECTIVE info read above so
            // both capabilities ride along untouched; null skips.
            try {
                effective?.let { info ->
                    info.eventTypes = required
                    info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_HAPTIC
                    info.notificationTimeout = 0L
                    info.flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                    info.motionEventSources = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    service.setServiceInfo(info)
                    if (nowMs - lastHealLogMs >= HEALTH_LOG_INTERVAL_MS) {
                        lastHealLogMs = nowMs
                        com.assistant.diagnostic.RuntimeLogger.log(
                            "A11Y_MASK_HEAL maskOk=$maskOk flagOk=$flagOk reasserted eventTypes=$required flags=FLAG_SEND_MOTION_EVENTS (effective was ${effective?.eventTypes})",
                            "SMART_ASSIST"
                        )
                    }
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
            val motionAge = if (SmartAssistAccessibilityEngine.lastMotionEventMs > 0L)
                nowMs - SmartAssistAccessibilityEngine.lastMotionEventMs else -1L
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "A11Y_FLOW maskOk=$maskOk anyEventAgeMs=$anyAge touchEventAgeMs=$touchAge motionEventAgeMs=$motionAge",
                    "SMART_ASSIST"
                )
            } catch (_: Throwable) { }
        }
    }
}
