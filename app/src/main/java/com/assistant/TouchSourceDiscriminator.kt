package com.assistant

import java.util.concurrent.ConcurrentHashMap

/**
 * TouchSourceDiscriminator (Phase 0 #1) — ghost root-cause fix.
 *
 * PROBLEM (verified at 208c4a86): the app has no discrimination between a
 * user touch and its own injected touch. UserTouchState.setTouching is
 * written ONLY from onAccessibilityEvent TYPE_TOUCH_INTERACTION_START/END
 * (SmartAssistAccessibilityEngine.kt:651-656) with zero source
 * discrimination. If an injected gesture raises START, the app treats its
 * own output as a user touch (GHOST-3 self-authorizing loop: app dispatches
 * → touch event → setTouching(true) → MOVE gate passes → app dispatches
 * again) and invalidates its own in-flight gesture envelope (A2:
 * onAccessibilityEvent START calls invalidateGestureEnvelopeOnManualTouch).
 * Separately, UserIntentVision infers holds from occluded control pixels —
 * an injected drag renders the joystick/buttons pressed identically to a
 * real finger, so the app suppresses its own MOVE (A3 autopilot
 * self-suppression).
 *
 * This engine registers every dispatch window (actionId, start, end) from
 * GestureExecutionAuthority — the single dispatch point — and exposes
 * isAppGestureActive(now), which the touch-state writer and the
 * hold-inference engine consult before believing a touch.
 *
 * TRADE-OFF (documented, fail-closed): a genuine user touch that lands
 * during our own stroke is ignored for authorization. That is the correct
 * direction — an ignored genuine touch costs one missed authorization,
 * while a believed injected touch sustains a ghost loop.
 *
 * SCOPE: this piece does NOT change UserTouchState.isTouching() semantics
 * (that is the Split-boolean piece), does NOT change the 2500 ms retention
 * (TouchQualityEngine depends on it — A4), and does NOT change
 * bypassTouchGate (that is the PerSourceTouchPolicy piece).
 */
object TouchSourceDiscriminator {

    // Guard margin after a gesture ends, to cover the event-delivery lag
    // between the OS finishing our gesture and the accessibility event
    // arriving. Small on purpose: 50 ms is ~1 frame at the 20 fps
    // processed rate.
    private const val GESTURE_END_GUARD_MS = 50L

    // Max age for a dispatch window with no completion callback. A gesture
    // whose callback is lost must not claim "app gesture active" forever.
    // 5000 ms is far beyond any real stroke (tens of ms), so a window
    // this old is a lost callback, not a live gesture.
    private const val STALE_WINDOW_MS = 5000L

    // Bound on the registry size. Windows are evicted on read (completed +
    // guard elapsed, or stale); the cap is a backstop against a leak if
    // completions stop arriving entirely. Dispatch is serial (one stroke
    // at a time, latch), so the live count is 1-2 in practice.
    private const val MAX_WINDOWS = 64

    private data class DispatchWindow(
        val actionId: String,
        val startMs: Long,
        @Volatile var endMs: Long = -1L
    )

    private val windows = ConcurrentHashMap<String, DispatchWindow>()

    /**
     * Register a dispatch window when GestureExecutionAuthority's
     * dispatchGesture returns true. startMs is captured just before the
     * dispatch call so the window covers the whole live gesture.
     */
    fun registerDispatch(actionId: String, startMs: Long) {
        prune(startMs)
        if (windows.size >= MAX_WINDOWS) {
            // Backstop: drop the oldest window so a new dispatch can always
            // register. Unreachable in practice (dispatch is serial, windows
            // expire 50 ms after completion); the cap only guards against a
            // leak if completions stop arriving.
            windows.entries.minByOrNull { it.value.startMs }?.let { windows.remove(it.key) }
        }
        windows[actionId] = DispatchWindow(actionId, startMs)
    }

    /**
     * Close a dispatch window when the gesture completes or is cancelled.
     * The window is NOT removed here — the guard margin after end still
     * counts as "our gesture live" for events in flight. prune() removes
     * it once the guard has elapsed.
     */
    fun completeDispatch(actionId: String, endMs: Long) {
        windows[actionId]?.let { it.endMs = endMs }
    }

    /**
     * True while any registered dispatch window is live at `now` — from
     * its start until its end plus the guard margin. A window with no end
     * (callback lost) counts as live until the stale timeout evicts it.
     */
    fun isAppGestureActive(now: Long): Boolean {
        prune(now)
        return windows.values.any { w ->
            now >= w.startMs && (w.endMs < 0 || now <= w.endMs + GESTURE_END_GUARD_MS)
        }
    }

    /** Evict windows whose guard has elapsed, and uncompleted windows older than the stale timeout. */
    private fun prune(now: Long) {
        val it = windows.entries.iterator()
        while (it.hasNext()) {
            val w = it.next().value
            val expired = (w.endMs >= 0 && now > w.endMs + GESTURE_END_GUARD_MS) ||
                (w.endMs < 0 && now - w.startMs > STALE_WINDOW_MS)
            if (expired) it.remove()
        }
    }

    fun reset() {
        windows.clear()
    }
}
