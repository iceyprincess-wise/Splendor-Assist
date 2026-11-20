package com.assistant



































































data class CarrierProfile(
    val name: String,
    val expectedRttMs: Int,
    val keepAliveSeconds: Int,
    val jitterToleranceMs: Int
)

/**
 * Detects the live carrier and selects its tuned profile (no extra permission
 * needed). Batch 6d: the V3 doc used to claim the admin could override the
 * baseline ping, wobble allowance and keepalive rhythm from the admin panel
 * — no such panel or preference exists, and the getters hardcoded the
 * override to 0. Every engine reads the detected-profile values below
 * (auto only); an admin override needs a real preference + writer first.
 */
