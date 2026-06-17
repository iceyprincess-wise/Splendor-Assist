package com.assistant.survival

import com.assistant.audit.SelfAuditRegistry

object OverlaySurvivalEngine {

    fun attached() {
        SelfAuditRegistry.update(
            "OVERLAY",
            "ATTACHED"
        )
    }

    fun detached() {
        SelfAuditRegistry.update(
            "OVERLAY",
            "DETACHED"
        )
    }

    fun destroyed() {
        SelfAuditRegistry.update(
            "OVERLAY",
            "DESTROYED"
        )
    }
}
