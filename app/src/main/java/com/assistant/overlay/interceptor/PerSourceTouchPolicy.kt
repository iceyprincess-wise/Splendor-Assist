package com.assistant.overlay.interceptor

import com.assistant.execution.ExecutionSource

/**
 * PerSourceTouchPolicy (Phase 0 #3) — replaces the global bypassTouchGate
 * with per-source touch rules.
 *
 * PROBLEM (GHOST-1, verified at 208c4a86): InterceptionRuntimeRegistry.bypassTouchGate
 * was a single global boolean, default true. It was introduced so keeper saves
 * fire while the user isn't touching — but it disabled the touch gate for EVERY
 * source: SMART_ASSIST, STUTTER, OCR, legacy. Any accepted request from any
 * engine dispatched with zero user touch. That was the primary ghost-touch
 * surface in the app.
 *
 * RULES (per source):
 * - GOALKEEPER, INTERCEPTION: AUTONOMOUS — do not require touch. They must
 *   save/intercept while the user isn't touching. This is the same definition
 *   as SmartAssistAccessibilityEngine.isEmergencySource() and
 *   ContributionRegistry.isEmergencySource(). The user can revoke autonomy
 *   via the ModMenu "bypass_touch_gate" switch: when the switch is OFF, even
 *   these sources require touch. The switch no longer affects any other source.
 * - SMART_ASSIST: REQUIRES touch. This is the main assist lane AND the OCR
 *   pipe (SmartAssistPipeline.createExecutionRequest uses SMART_ASSIST), so an
 *   OCR-triggered swipe with no user touch is now rejected (closes GHOST-6).
 *   Discrete actions (PASS/SHOT/CROSS/DEFEND) ride SMART_ASSIST; their
 *   "explicit intent trigger" is enforced upstream by the RDL never-fight
 *   gate and UserIntentVision slot holds, where intent is known — the
 *   authority enforces the touch requirement, not a second intent check.
 *   No new intent mechanism is invented here.
 * - STUTTER: REQUIRES touch. STUTTER is a re-assert of an existing hold
 *   (BurstForensicsEngine). The re-assert is only legitimate while the user
 *   is still holding; if the user has released, re-asserting extends a hold
 *   they released (GHOST-7). The 2.5 s isTouching() retention covers brief
 *   touch-state gaps, so a legitimate re-assert while the user still holds
 *   is not dropped.
 * - null / unknown source: REQUIRES touch (fail-closed). Any caller that does
 *   not declare its source is treated as SMART_ASSIST. GestureExecutionAuthority
 *   resolves a null source to SMART_ASSIST before calling requiresTouch().
 *
 * TOUCH SIGNAL: the gate reads UserTouchState.isTouching() for now. The
 * Split-boolean piece (Phase 0 #2) adds UserTouchState.isUserTouchAuthorizing()
 * — a source-discriminated, staleness-guarded, short-window signal. When it
 * lands, the touch check in GestureExecutionAuthority should call
 * isUserTouchAuthorizing() instead of isTouching(). This policy does NOT
 * duplicate the split-boolean logic; it only decides WHO must be touching.
 */
object PerSourceTouchPolicy {

    /**
     * Whether a dispatch from [source] requires an authorizing user touch.
     * Returns false only for the autonomous sources (GOALKEEPER, INTERCEPTION)
     * while the user has left the ModMenu bypass switch ON.
     */
    fun requiresTouch(source: ExecutionSource): Boolean {
        return when (source) {
            ExecutionSource.GOALKEEPER, ExecutionSource.INTERCEPTION -> {
                // Autonomous by policy, revocable by the user via the ModMenu
                // "bypass_touch_gate" switch. Switch ON (default) = autonomous;
                // switch OFF = even keeper/interception require touch.
                !InterceptionRuntimeRegistry.bypassTouchGate
            }
            ExecutionSource.SMART_ASSIST, ExecutionSource.STUTTER -> true
        }
    }
}
