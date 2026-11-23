package com.assistant

data class SmartAssistState(
    val enabled: Boolean = true,
    val panicMode: Boolean = false,
    // AUTO-PANIC GATE: the user's Panic switch choice, set ONLY by the
    // user-facing toggles (mod menu, Smart Assist control room) via
    // updatePanicMode(). Transient, never persisted — defaults OFF each
    // session, like panicMode. The OCR match-detection reads this, not
    // panicMode, so it can sustain panic the user enabled without ever
    // turning panic on by itself (which is what re-activated panic every
    // ~5s despite the switch being OFF).
    val panicUserEnabled: Boolean = false,
    val configuration: SmartAssistConfiguration = SmartAssistConfiguration(),
    val lastUpdated: Long = System.currentTimeMillis()
)
