package com.assistant.survival

/**
 * Liveness of the overlay node, as opposed to SelfAuditRegistry's
 * event audit (ATTACHED/DESTROYED).
 *
 * No62-D18: this registry was DEAD — update() had zero callers and
 * state() had zero readers repo-wide, so the overlay's liveness was
 * never observed anywhere. It is now wired: OverlaySurvivalEngine
 * seeds it (attached() -> update(true), destroyed() -> update(false)),
 * heartbeat() refreshes it from the OverlayService capture path on
 * every delivered frame, and DashboardInjector reads state() into the
 * metrics text next to the SelfAuditRegistry snapshot.
 *
 * Contract: ACTIVE requires a heartbeat within STALE_AFTER_MS.
 * attached() seeds the clock, so a freshly attached overlay reads
 * ACTIVE; if frames stop arriving for longer than STALE_AFTER_MS
 * while the service lives, it reads DEGRADED (capture stalled, not
 * overlay gone); destroyed() reads OFFLINE. Before the wiring, a
 * single attach() would have gone DEGRADED after 120s even while
 * healthy, because nothing ever refreshed lastSeen — the heartbeat
 * is what makes ACTIVE truthful, not the attach event alone.
 */
object OverlaySurvivalRegistry {

    /** No frames for this long while attached means capture stalled. */
    const val STALE_AFTER_MS: Long = 120000L

    @Volatile
    private var attached = false

    @Volatile
    private var lastSeen = 0L

    @Synchronized
    fun update(
        isAttached: Boolean
    ) {

        attached = isAttached

        lastSeen =
            System.currentTimeMillis()
    }

    @Synchronized
    fun state(): String {

        val age =
            System.currentTimeMillis() -
            lastSeen

        return when {

            !attached ->
                "OFFLINE"

            age > STALE_AFTER_MS ->
                "DEGRADED"

            else ->
                "ACTIVE"
        }
    }
}
