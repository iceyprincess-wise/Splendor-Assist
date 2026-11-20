package com.assistant

/**
 * Midfield line result (queue item 5, batch midfield).
 *
 * The measured midfield line: the middle band of user-team players.
 * Field list mirrors DefensiveLineResult EXCEPT there is deliberately
 * no count field — the band size has no live reader anywhere (it is
 * used inside the engine only, to form the means), and the full-effect
 * rule (Ed, item 13) says a field with no named live reader is dropped
 * rather than shipped dead. Every field here has a named live reader:
 * found / averageX / minX / maxX are read by the hold contributor
 * (gate, hold target, clamp bounds); confidence is read by
 * MidfieldCompactnessEngine (the line-confidence input to the same
 * native entry the defensive compactness uses).
 */
data class MidfieldLineResult(
    val found: Boolean,
    val averageX: Float = 0f,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val confidence: Float = 0f
)
