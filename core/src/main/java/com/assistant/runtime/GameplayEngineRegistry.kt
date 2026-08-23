package com.assistant.runtime

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object GameplayEngineRegistry {
    private val contributors = CopyOnWriteArrayList<GameplayContributor>()
    private val registeredNames = ConcurrentHashMap<String, Boolean>()
    private val lastContribution = ConcurrentHashMap<String, EngineContribution>()
    private val contributed = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val collectCycles = AtomicLong(0L)
    
    // Telemetry
    private val registrationGeneration = AtomicLong(0L)
    private val collisions = AtomicLong(0L)
    @Volatile private var warmUpCompletionTimestamp: Long = 0L

    fun register(contributor: GameplayContributor) {
        // Atomic putIfAbsent guarantees only one thread can register a given engineName
        if (registeredNames.putIfAbsent(contributor.engineName, true) != null) {
            collisions.incrementAndGet()
            RuntimeLogger.log(
                "REGISTRY COLLISION: ${contributor.engineName} (${contributor.javaClass.name}) rejected – name already owned",
                "RUNTIME"
            )
            return
        }
        contributors.add(contributor)
        registrationGeneration.incrementAndGet()
        try { 
            contributor.initialize() 
        } catch (t: Throwable) {
            RuntimeLogger.log("Engine init failed ${contributor.engineName}: ${t.message}", "RUNTIME")
        }
    }

    fun warmAll() {
        contributors.forEach { c -> 
            try { 
                c.warmUp() 
            } catch (t: Throwable) {
                RuntimeLogger.log("Engine warmUp failed ${c.engineName}: ${t.message}", "RUNTIME")
            } 
        }
        warmUpCompletionTimestamp = System.currentTimeMillis()
        RuntimeLogger.log(
            "REGISTRY WARM COMPLETE: ${contributors.size} engines warmed at $warmUpCompletionTimestamp",
            "RUNTIME"
        )
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
            } catch (t: Throwable) {
                val n = (failures[c.engineName] ?: 0L) + 1L
                failures[c.engineName] = n
                if (n == 1L || n % 50L == 0L) {
                    try {
                        RuntimeLogger.log(
                            "ENGINE FAILURE ${c.engineName} x$n: " +
                                (t.message ?: t.javaClass.simpleName),
                            "RUNTIME"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
        return out
    }

    fun resetAll() {
        contributors.forEach { c -> try { c.reset() } catch (_: Throwable) {} }
        contributors.clear()
        registeredNames.clear()
        lastContribution.clear(); contributed.clear(); failures.clear()
        collectCycles.set(0L)
        registrationGeneration.set(0L)
        collisions.set(0L)
        warmUpCompletionTimestamp = 0L
    }

    fun registryRuntimeSnapshot(): Map<String, Any> = mapOf(
        "engines" to contributors.size,
        "collectCycles" to collectCycles.get(),
        "names" to contributors.joinToString(",") { it.engineName },
        "contributed" to contributed.toString(),
        "failures" to failures.toString(),
        "generation" to registrationGeneration.get(),
        "collisions" to collisions.get(),
        "warmUpTimestamp" to warmUpCompletionTimestamp
    )

    fun engineStates(): List<Map<String, Any>> = contributors.map { c ->
        val last = lastContribution[c.engineName]
        mapOf(
            "engine" to c.engineName,
            "capabilities" to c.capabilities.joinToString(",") ,
            "contributions" to (contributed[c.engineName] ?: 0L),
            "failures" to (failures[c.engineName] ?: 0L),
            "lastAction" to (last?.actionClass?.name ?: "none"),
            "lastWeight" to (last?.weight ?: 0f)
        )
    }
}
