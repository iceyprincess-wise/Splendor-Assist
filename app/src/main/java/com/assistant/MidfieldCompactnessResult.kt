package com.assistant

/**
 * Midfield compactness result (queue item 5, batch midfield).
 *
 * Carries ONLY compactness + confidence — the two fields with live
 * readers in the defensive version. The horizontal/vertical fields of
 * DefensiveCompactnessResult have ZERO readers anywhere and are NOT
 * repeated here (full-effect rule, Ed item 13). Readers, both in
 * the hold contributor: compactness is the second half of the
 * broken-midfield gate, confidence gates that half (a spread-out
 * reading with zero confidence must not hold the player).
 */
data class MidfieldCompactnessResult(
    val compactness: Float = 0f,
    val confidence: Float = 0f
)
