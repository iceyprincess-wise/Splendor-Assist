package com.assistant.survival

import com.assistant.audit.SelfAuditRegistry

object OverlaySurvivalEngine {

    // No62-D18: these two calls seed OverlaySurvivalRegistry, which
    // was dead (zero callers) until this round. attached() marks the
    // node live; destroyed() marks it OFFLINE. heartbeat() below is
    // the periodic refresh that keeps it ACTIVE while capture runs.
    fun attached() {
        OverlaySurvivalRegistry.update(true)
        SelfAuditRegistry.update(
            "OVERLAY",
            "ATTACHED"
        )
    }

    // No48-D7: detached() is REMOVED. It had no call site anywhere in
    // the repo, and none can exist: the overlay view is removed only in
    // OverlayService.onDestroy(), which reports DESTROYED — there is no
    // path where the overlay is detached while the service lives on.
    // Keeping it would pretend DETACHED is a reachable audit state.
    // The registry's status vocabulary is now ATTACHED / DESTROYED.

    fun destroyed() {
        OverlaySurvivalRegistry.update(false)
        SelfAuditRegistry.update(
            "OVERLAY",
            "DESTROYED"
        )
    }

    /**
     * Liveness heartbeat, called from the OverlayService capture path
     * on every delivered frame. Without it the registry would read
     * DEGRADED 120s after attach even while healthy, because attached()
     * alone only seeds the clock once.
     */
    fun heartbeat() {
        OverlaySurvivalRegistry.update(true)
    }
}
