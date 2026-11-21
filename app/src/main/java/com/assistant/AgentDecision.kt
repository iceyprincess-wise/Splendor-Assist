package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class AgentDecision(
    val action: AgentAction,
    val priority: Int,
    val reason: String
)

// Letter A item 16: the creation-timestamp field deleted — stamped
// at construction but never read anywhere. A decision is executed in
// the same InAppAgentCore tick that creates it, so a staleness check
// on it could never observe a stale decision; the field had no live
// consumer to wire.

/**
 * Deterministic first-generation agent policy.
 *
 * It is intentionally evidence-driven:
 * no action is taken unless an existing runtime health surface
 * provides a concrete reason.
 */
