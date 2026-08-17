package com.assistant.adapter.smartassist

/**
 * Actions available to the in-app runtime agent.
 *
 * The agent does not directly manipulate gameplay input.
 * It invokes existing authoritative recovery/orchestration layers.
 */
sealed class AgentAction {
    object ObserveOnly : AgentAction()
    object RunSelfHealCheck : AgentAction()
    object RefreshPerformance : AgentAction()
}
