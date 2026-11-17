package com.assistant.survival

import com.assistant.audit.SelfAuditRegistry

object OverlaySurvivalEngine {

    fun attached() {
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
        SelfAuditRegistry.update(
            "OVERLAY",
            "DESTROYED"
        )
    }
}
