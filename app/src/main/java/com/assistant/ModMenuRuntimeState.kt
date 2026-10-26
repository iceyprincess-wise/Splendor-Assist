package com.assistant

/**
 * Central runtime state for the in-game Mod Menu.
 *
 * Single source of truth for all mod menu toggle/switch values.
 * Read by the Mod Menu UI and consumed by engine runtime authorities
 * to affect live gameplay behavior.
 */
object ModMenuRuntimeState {

    // --- Master controls ---
    var isMenuVisible: Boolean = false
        private set

    var isEngineMasterEnabled: Boolean = false

    // --- Smart Assist controls ---
    var smartAssistEnabled: Boolean = false
    var smartAssistAuthority: Float = 1.0f
    var smartAssistPanicMode: Boolean = false

    // --- Smart Assist thresholds ---
    var smartAssistPassThreshold: Float = 0.5f
    var smartAssistShotThreshold: Float = 0.5f
    var smartAssistCrossThreshold: Float = 0.5f

    // --- Goalkeeper controls ---
    var goalkeeperEnabled: Boolean = false
    var goalkeeperAggressiveMode: Boolean = false
    var goalkeeperPositioning: Float = 0.5f
    var goalkeeperReactions: Float = 0.5f

    // --- Interception controls ---
    var interceptionEnabled: Boolean = false
    var interceptionAutoIntercept: Boolean = false
    var interceptionAwareness: Int = 50
    var interceptionPrediction: Int = 50

    // --- Runtime live status ---
    var gameForeground: Boolean = false
    var captureActive: Boolean = false
    var frameTrusted: Boolean = false
    var decisionsCount: Int = 0
    var routedCount: Int = 0
    var executionAccepted: Int = 0
    var executionRejected: Int = 0

    fun show() { isMenuVisible = true }
    fun hide() { isMenuVisible = false }
    fun toggle() { isMenuVisible = !isMenuVisible }

    fun resetToDefaults() {
        isEngineMasterEnabled = false
        smartAssistEnabled = false
        smartAssistAuthority = 1.0f
        smartAssistPanicMode = false
        smartAssistPassThreshold = 0.5f
        smartAssistShotThreshold = 0.5f
        smartAssistCrossThreshold = 0.5f
        goalkeeperEnabled = false
        goalkeeperAggressiveMode = false
        goalkeeperPositioning = 0.5f
        goalkeeperReactions = 0.5f
        interceptionEnabled = false
        interceptionAutoIntercept = false
        interceptionAwareness = 50
        interceptionPrediction = 50
    }
}
