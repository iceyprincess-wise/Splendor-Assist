package com.assistant

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UserTouchState {
    private val touching = AtomicBoolean(false)
    private val lastTouchTimeMs = AtomicLong(0L)
    // Dynamic window for active touch retention (ms)
    private const val TOUCH_RETENTION_WINDOW_MS = 2500L

    // Phase 0 #2 — authorization windows for isUserTouchAuthorizing().
    // These do NOT change isTouching() (stall evidence keeps the 2500 ms
    // retention). They bound the AUTHORIZING signal only.
    //
    // MAX_CONTINUOUS_AUTHORIZATION_MS (5000): the longest the touching
    // flag may stay continuously true without a fresh START event before
    // authorization is withdrawn. A genuine hold re-raises START
    // periodically (new interaction / re-grip), so a live hold refreshes
    // lastTouchStartMs; a stuck flag (lost END event, A1) never does.
    // 5 s is long enough to cover any genuine continuous hold between
    // START re-raises and short enough that a stuck-true leak stops
    // authorizing within 5 s instead of forever.
    private const val MAX_CONTINUOUS_AUTHORIZATION_MS = 5000L
    //
    // AUTHORIZATION_RETENTION_MS (500): after lift, authorization
    // expires within 500 ms — long enough to cover the frame gap
    // between the END event and the next gate check (~50 ms at the
    // 20 fps processed rate), short enough to kill the 2.5 s ghost
    // window (GHOST-2) for authorization. This is the window that is
    // shorter than the 2500 ms retention; the 5000 ms cap above bounds
    // a continuously-true flag instead. The 2500 ms retention stays
    // for TouchQualityEngine stall evidence only.
    private const val AUTHORIZATION_RETENTION_MS = 500L

    // Timestamp of the most recent TYPE_TOUCH_INTERACTION_START, set
    // in setTouching(true) alongside lastTouchTimeMs. Kept separate so
    // the authorization guard does not silently depend on the retention
    // field's semantics if they ever change.
    private val lastTouchStartMs = AtomicLong(0L)

    fun setTouching(value: Boolean) {
        touching.set(value)
        if (value) {
            lastTouchTimeMs.set(System.currentTimeMillis())
            lastTouchStartMs.set(System.currentTimeMillis())
        }
    }

    fun isTouching(): Boolean {
        if (touching.get()) return true
        val elapsed = System.currentTimeMillis() - lastTouchTimeMs.get()
        return elapsed in 0..TOUCH_RETENTION_WINDOW_MS
    }

    // Phase 0 #2 — the AUTHORIZATION signal for the MOVE/EVADE gates.
    // isTouching() serves two incompatible jobs (authorization vs.
    // TouchQualityEngine stall evidence) and cannot be fixed in place:
    // shortening its retention would break stall detection (A4), and
    // its touching.get() branch has no age guard, so a lost END event
    // leaves it true forever (A1). This function is the gate signal;
    // isTouching() is left exactly unchanged for stall evidence.
    //
    // Fail-closed: any doubt (no recent START, app gesture active,
    // stale flag) returns false. Authorization is never granted on a
    // stale or self-generated signal.
    //
    // NOTE: TouchSourceDiscriminator is the parallel Phase 0 #1 piece
    // (Emeka). This reference resolves when both pieces land together;
    // do not duplicate the discriminator logic here.
    fun isUserTouchAuthorizing(now: Long = System.currentTimeMillis()): Boolean {
        // (1) Source discrimination: our own injected gesture is not a
        // user touch. While our gesture is live, no authorization.
        if (TouchSourceDiscriminator.isAppGestureActive(now)) return false
        // (2) Stuck-true guard: the flag may be continuously true for at
        // most MAX_CONTINUOUS_AUTHORIZATION_MS without a fresh START.
        // Past that, the END event was lost — stop authorizing.
        if (touching.get()) {
            return (now - lastTouchStartMs.get()) in 0..MAX_CONTINUOUS_AUTHORIZATION_MS
        }
        // (3) Short post-lift window: after lift, authorization expires
        // within AUTHORIZATION_RETENTION_MS (lastTouchTimeMs is the last
        // START time). The 2.5 s retention does not apply here.
        val elapsed = now - lastTouchTimeMs.get()
        return elapsed in 0..AUTHORIZATION_RETENTION_MS
    }
}
