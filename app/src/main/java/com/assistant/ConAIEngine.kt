package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass

import com.assistant.runtime.EngineContribution


import com.assistant.runtime.RuntimeFrame


















import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object ConAIEngine {
    private val metaDecisions = AtomicLong(0L)
    private val metaOverrides = AtomicLong(0L)

    fun arbitrate(
        frame: RuntimeFrame,
        candidates: List<EngineContribution>,
        netHold: Boolean
    ): EngineContribution? {
        if (candidates.isEmpty()) return null
        metaDecisions.incrementAndGet()

        val validCandidates = candidates.filter { c ->
            c.weight.isFinite() && c.weight > 0f &&
                !(netHold && c.actionClass != ActionClass.MOVE && c.actionClass != ActionClass.DEFEND)
        }
        if (validCandidates.isEmpty()) return null

        val tacticalCandidates = validCandidates.filter {
            it.actionClass != ActionClass.MOVE && it.actionClass != ActionClass.NONE
        }

        if (frame.hasBall && tacticalCandidates.isNotEmpty()) {
            val bestTactical = tacticalCandidates.maxByOrNull { it.weight * it.authority }
            if (bestTactical != null && bestTactical.authority >= 0.30f) {
                metaOverrides.incrementAndGet()
                return bestTactical
            }
        }

        if (!frame.hasBall) {
            val defCandidates = validCandidates.filter {
                it.actionClass == ActionClass.DEFEND || it.actionClass == ActionClass.KEEPER
            }
            val bestDef = defCandidates.maxByOrNull { it.weight * it.authority }
            if (bestDef != null && bestDef.authority >= 0.25f) {
                return bestDef
            }
        }

        val hasTactical = tacticalCandidates.isNotEmpty()
        return validCandidates.maxByOrNull { c ->
            val scale = if (c.actionClass == ActionClass.MOVE && hasTactical) 0.35f else 1.0f
            c.weight * c.authority * scale
        }
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "metaDecisions" to metaDecisions.get(),
        "metaOverrides" to metaOverrides.get()
    )
}

/* ========
ActionVerifier
======== */
