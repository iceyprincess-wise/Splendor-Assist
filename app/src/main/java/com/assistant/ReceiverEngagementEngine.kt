object ReceiverEngagementEngine {

    fun evaluate(
        distance:Float,
        retention:Float
    ):ReceiverEngagementResult {

        val distanceFactor =
            (distance.coerceIn(0f,1000f) / 1000f)

        val confidence =
            (
                0.45f +
                retention * 0.35f +
                (1f - distanceFactor) * 0.20f
            ).coerceIn(0f,1f)

        val engagementBoost =
            1f +
            (retention * 0.70f) +
            ((1f - distanceFactor) * 0.30f)

        val interceptionRisk =
            (
                (1f - retention) * 0.70f +
                distanceFactor * 0.30f
            ).coerceIn(0f,1f)

        return ReceiverEngagementResult(
            engagementBoost = engagementBoost,
            interceptionRisk = interceptionRisk,
            confidence = confidence
        )
    }
}
/* ======
ReceiverEngagementEngine Anchor
====== */

/* ========
RuntimeHealthMonitor
======== */
/*
 * RuntimeHealthMonitor answers one question:
 * "Is the runtime alive, stale, or degraded right now?"
 *
 * It does not own gameplay logic. It reads health surfaces already present
 * in the architecture and reports only REAL failures:
 *  - frame/decision freshness is measured by counter PROGRESS between
 *    evaluations (the old math compared 'now' against a timestamp taken
 *    inside the same call, so frame-stale could never fire and
 *    decision-stale fired for the wrong reason)
 *  - the decision loop runs once per assembled frame; when the frame flow
 *    itself is paused (menus, control rooms, game off screen) the loop is
 *    idle by design - the cause is reported as frame-stale, never blamed
 *    on the decision loop
 *  - a dispatcher that has never been asked to dispatch is idle, not stale
 *  - booster health reads the CROSS-PROCESS adapter heartbeats: the
 *    adapters live in their own processes, so only the persisted
 *    heartbeats are visible here, and only FRESH ones count
 */
