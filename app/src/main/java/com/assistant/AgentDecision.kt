package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class AgentDecision(
    val action: AgentAction,
    val priority: Int,
    val reason: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Deterministic first-generation agent policy.
 *
 * It is intentionally evidence-driven:
 * no action is taken unless an existing runtime health surface
 * provides a concrete reason.
 */
