package com.assistant.survival

import com.assistant.audit.SelfAuditRegistry

object AccessibilitySurvivalEngine {

    fun connected() {
        SelfAuditRegistry.update(
            "ACCESSIBILITY",
            "CONNECTED"
        )
    }

    fun interrupted() {
        SelfAuditRegistry.update(
            "ACCESSIBILITY",
            "INTERRUPTED"
        )
    }

    fun missing() {
        SelfAuditRegistry.update(
            "ACCESSIBILITY",
            "MISSING"
        )
    }
}
