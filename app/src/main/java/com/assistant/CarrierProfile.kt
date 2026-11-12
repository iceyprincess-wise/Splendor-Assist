package com.assistant



































































data class CarrierProfile(
    val name: String,
    val expectedRttMs: Int,
    val keepAliveSeconds: Int,
    val jitterToleranceMs: Int
)

/**
 * Detects the live carrier and selects its tuned profile (no extra permission
 * needed). V3: the admin can OVERRIDE the baseline ping, wobble allowance and
 * keepalive rhythm from the admin panel (0 = automatic, follow the detected
 * carrier). Every engine reads the override-aware values below, so an admin
 * override takes effect stack-wide on the next tick.
 */
