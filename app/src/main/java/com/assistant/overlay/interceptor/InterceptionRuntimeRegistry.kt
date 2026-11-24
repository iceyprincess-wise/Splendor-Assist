package com.assistant.overlay.interceptor

object InterceptionRuntimeRegistry {

    @Volatile
    var enabled: Boolean = true

    @Volatile var autoIntercept: Boolean = true

    @Volatile var armed: Boolean = true

    // Phase 0 #3 (PerSourceTouchPolicy): RE-SCOPED. This flag used to bypass
    // the touch gate GLOBALLY (every source dispatched without touch — GHOST-1).
    // It now governs ONLY the autonomous sources (KEEPER/INTERCEPTION):
    // PerSourceTouchPolicy.requiresTouch() returns !bypassTouchGate for them.
    // SMART_ASSIST (incl. the OCR pipe) and STUTTER always require touch,
    // regardless of this flag. The SharedPreferences key ("bypass_touch_gate",
    // default true) and the ModMenu switch are unchanged, so an existing user
    // preference is preserved; only the scope changed.
    @Volatile var bypassTouchGate: Boolean = true

    @Volatile
    var awareness: Int = 100

    @Volatile
    var prediction: Int = 100
}
