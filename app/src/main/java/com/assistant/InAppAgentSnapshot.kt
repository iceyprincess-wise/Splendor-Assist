data class InAppAgentSnapshot(
    val running: Boolean,
    val cycles: Long,
    val lastObservationMs: Long,
    val lastAction: String,
    val lastReason: String,
    val lastVerification: String,
    val lastVerified: Boolean
)

/**
 * Single in-process agent coordinator.
 *
 * Pipeline:
 *
 * RuntimeObservation
 *        ↓
 * AgentDecisionPolicy
 *        ↓
 * AgentAction
 *        ↓
 * existing runtime mechanism
 *        ↓
 * ActionVerifier
 *
 * This is not a second gameplay engine and does not replace
 * RuntimeCoordinator, RuntimeHealthMonitor or RuntimeSelfHealEngine.
 */
