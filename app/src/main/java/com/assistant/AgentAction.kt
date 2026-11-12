package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























sealed class AgentAction {
    object ObserveOnly : AgentAction()
    object RunSelfHealCheck : AgentAction()
    object RefreshPerformance : AgentAction()
    object ReigniteFleet : AgentAction()
}

/* ========
AgentDecision
======== */
