package com.assistant.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object GameplayEngineRegistry {
    private val contributors = CopyOnWriteArrayList<GameplayContributor>()
    private val lastContribution = ConcurrentHashMap<String, EngineContribution>()
    private val contributed = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val collectCycles = AtomicLong(0L)

    fun register(contributor: GameplayContributor) {
        if (contributors.any { it.engineName == contributor.engineName }) return
        contributors.add(contributor)
        try { contributor.initialize() } catch (_: Throwable) {}
    }

    fun warmAll() {
        contributors.forEach { c -> try { c.warmUp() } catch (_: Throwable) {} }
    }

    fun collect(frame: RuntimeFrame): List<EngineContribution> {
        collectCycles.incrementAndGet()
        val out = ArrayList<EngineContribution>(contributors.size)
        for (c in contributors) {
            try {
                c.update(frame)
                val contribution = c.contribute(frame) ?: continue
                lastContribution[c.engineName] = contribution
                contributed[c.engineName] = (contributed[c.engineName] ?: 0L) + 1L
                out.add(contribution)
            } catch (_: Throwable) {
                failures[c.engineName] = (failures[c.engineName] ?: 0L) + 1L
            }
        }
        return out
    }

    fun resetAll() {
        contributors.forEach { c -> try { c.reset() } catch (_: Throwable) {} }
        lastContribution.clear(); contributed.clear(); failures.clear()
        collectCycles.set(0L)
    }

    fun registryRuntimeSnapshot(): Map<String, Any> = mapOf(
        "engines" to contributors.size,
        "collectCycles" to collectCycles.get(),
        "names" to contributors.joinToString(",") { it.engineName },
        "contributed" to contributed.toString(),
        "failures" to failures.toString()
    )

    fun engineStates(): List<Map<String, Any>> = contributors.map { c ->
        val last = lastContribution[c.engineName]
        mapOf(
            "engine" to c.engineName,
            "capabilities" to c.capabilities.joinToString(","),
            "contributions" to (contributed[c.engineName] ?: 0L),
            "lastAction" to (last?.actionClass?.name ?: "none"),
            "lastWeight" to (last?.weight ?: 0f)
        )
    }
}
