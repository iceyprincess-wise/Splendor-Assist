package com.assistant.runtime

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// ContributionResult global singleton replaced with thread-confined ArrayList snapshot return in collect() to eliminate data races and torn reads.

object GameplayEngineRegistry {
    private val contributors = CopyOnWriteArrayList<GameplayContributor>()
    private val registeredNames = ConcurrentHashMap<String, Boolean>()
    private val lastContribution = ConcurrentHashMap<String, EngineContribution>()
    private val contributed = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val collectCycles = AtomicLong(0L)
    // SPLENDOR_F31_TRUST_VISIBILITY: count frames rejected before contributors ever run.
    private val untrustedSkipped = AtomicLong(0L)

    private val registrationGeneration = AtomicLong(0L)
    private val collisions = AtomicLong(0L)
    private val sessionEpoch = AtomicLong(0L)
    @Volatile private var warmUpCompletionTimestamp: Long = 0L
    private val registrationLock = Any()

    fun getRegisteredNames(): Set<String> = registeredNames.keys.toSet()

    // GER-D6 (batch 5): lastContribution was write-only — collect() stored
    // the latest accepted contribution per engine and nothing ever read it.
    // Read-only snapshot for the dashboard (DashboardInjector renders it);
    // a copy, so callers can never mutate the registry's live map.
    fun lastContributionSnapshot(): Map<String, EngineContribution> = lastContribution.toMap()

    fun register(contributor: GameplayContributor): Boolean {
        synchronized(registrationLock) {
            if (registeredNames.putIfAbsent(contributor.engineName, true) != null) {
                collisions.incrementAndGet()
                RuntimeLogger.log("REGISTRY COLLISION: ${contributor.engineName} rejected", "RUNTIME")
                return false
            }
            var initSuccess = true
            try {
                contributor.initialize()
            } catch (t: Throwable) {
                initSuccess = false
                RuntimeLogger.log("Engine init failed ${contributor.engineName}: ${t.message}", "RUNTIME")
            }
            if (initSuccess) {
                contributors.add(contributor)
                registrationGeneration.incrementAndGet()
            } else {
                registeredNames.remove(contributor.engineName)
            }
            return initSuccess
        }
    }

    fun warmAll(): List<String> {
        // MEDIUM FIX: Removed full-lock around warmUp(). CopyOnWriteArrayList allows safe iteration.
        // This prevents serializing the lifecycle activity for the entire warm-up duration on Helio G81.
        // GER-D4: warmAll runs lock-free by design, so a concurrent resetAll()
        // can clear the registry mid-warm. sessionEpoch is bumped by resetAll()
        // only; if it moved, the warm-up this call performed belongs to a dead
        // session, so the completion timestamp must not be published for the
        // (now empty) registry.
        val epochAtStart = sessionEpoch.get()
        val failed = mutableListOf<String>()
        val snapshot = contributors.toList()
        for (c in snapshot) {
            try {
                c.warmUp()
            } catch (t: Throwable) {
                failed.add(c.engineName)
                RuntimeLogger.log("Engine warmUp failed ${c.engineName}: ${t.message}", "RUNTIME")
            }
        }
        if (sessionEpoch.get() == epochAtStart) {
            warmUpCompletionTimestamp = System.currentTimeMillis()
            RuntimeLogger.log("REGISTRY WARM COMPLETE: ${snapshot.size} engines. Failures: ${failed.size}", "RUNTIME")
        } else {
            RuntimeLogger.log("REGISTRY WARM SUPERSEDED: reset during warm-up; timestamp not published (${snapshot.size} engines attempted, ${failed.size} failures)", "RUNTIME")
        }
        return failed
    }

    fun collect(frame: RuntimeFrame): List<EngineContribution> {
        collectCycles.incrementAndGet()
        // GER-D7: same epoch guard as warmAll (GER-D4). resetAll() clears the
        // bookkeeping maps under registrationLock while collect() runs
        // lock-free; a reset landing mid-collect must not let the writes below
        // resurrect entries for engines that are no longer registered.
        val epochAtStart = sessionEpoch.get()
        // SPLENDOR_V43_FAST_GATE: Instant exit on untrusted frames (saves 54 engine calls)
        // GER-D2: was 37; 54 contributors are registered
        // (AppContributorRegistration.EXPECTED_CONTRIBUTOR_COUNT).
        if (!frame.trusted) {
            // SPLENDOR_F31_TRUST_VISIBILITY: trust-blocked frames are now counted and logged
            // instead of vanishing. The empty return is unchanged on purpose: an untrusted
            // frame must still yield no contributions, but the starvation becomes observable.
            val skipped = untrustedSkipped.incrementAndGet()
            if (skipped == 1L || skipped % 100L == 0L) {
                RuntimeLogger.log(
                    "TRUST_SKIPPED x$skipped: untrusted frame returned empty before contributors "
                        + "(core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt collect())",
                    "RUNTIME"
                )
            }
            return emptyList()
        }
        val results = ArrayList<EngineContribution>(contributors.size)
        for (c in contributors) {
            try {
                // SPLENDOR_V43_FAST_GATE: O(1) capability culling before heavy math.
                // Inside the try so a throwing gate counts as that contributor's
                // failure instead of killing the whole collect loop.
                if (!c.fastGate(frame)) continue
                c.update(frame)
                val contribution = c.contribute(frame) ?: continue
                if (contribution.engine != c.engineName ||
                    contribution.actionClass == ActionClass.NONE ||
                    !contribution.targetX.isFinite() || !contribution.targetY.isFinite() ||
                    !contribution.authority.isFinite() || !contribution.confidence.isFinite() ||
                    contribution.weight <= 0f || contribution.durationHintMs <= 0L ||
                    contribution.controlSlot !in 0..4 ||
                    (contribution.aimOriginX == null) != (contribution.aimOriginY == null) ||
                    contribution.aimOriginX?.isFinite() == false ||
                    contribution.aimOriginY?.isFinite() == false) continue
                if (sessionEpoch.get() == epochAtStart) {
                    lastContribution[c.engineName] = contribution
                    contributed.compute(c.engineName) { _, v -> (v ?: 0L) + 1L }
                }
                results.add(contribution)
            } catch (t: Throwable) {
                var currentFailures = 1L
                failures.compute(c.engineName) { _, v ->
                    val next = (v ?: 0L) + 1L
                    currentFailures = next
                    next
                }
                if (currentFailures == 1L || currentFailures % 50L == 0L) {
                    RuntimeLogger.log("ENGINE FAILURE ${c.engineName} x$currentFailures: ${t.message ?: t.javaClass.simpleName}", "RUNTIME")
                }
            }
        }
        return results
    }

    fun resetAll() {
        synchronized(registrationLock) {
            sessionEpoch.incrementAndGet()
            // GER-D3: a throwing reset() used to vanish inside an empty catch.
            // Log it like warmUp/collect failures so a broken engine reset is visible.
            contributors.forEach { c -> try { c.reset() } catch (t: Throwable) { RuntimeLogger.log("Engine reset failed ${c.engineName}: ${t.message}", "RUNTIME") } }
            contributors.clear()
            registeredNames.clear()
            lastContribution.clear(); contributed.clear(); failures.clear()
            collectCycles.set(0L)
            untrustedSkipped.set(0L)
            registrationGeneration.set(0L)
            collisions.set(0L)
            warmUpCompletionTimestamp = 0L
        }
    }

    // SPLENDOR_V18_REGISTRY_HEALTH_BEGIN
    fun contributorCount(): Int = contributors.size

    fun collectCycleCount(): Long = collectCycles.get()

    // SPLENDOR_F31_TRUST_VISIBILITY
    fun untrustedSkippedCount(): Long = untrustedSkipped.get()

    fun isExpectedRegistered(expected: Int): Boolean = contributors.size == expected
    // SPLENDOR_V18_REGISTRY_HEALTH_END

    fun registryRuntimeSnapshot(): Map<String, Any> = mapOf(
        "engines" to contributors.size,
        "collectCycles" to collectCycles.get(),
        // GER-D1: "contributed" was maintained but never exposed, while
        // GlobalCrashHandler and SmartAssistControlRoomActivity both read
        // snap["contributed"] and printed null. Total accepted contributions
        // since the last reset.
        "contributed" to contributed.values.sum(),
        "names" to contributors.joinToString(",") { it.engineName },
        "generation" to registrationGeneration.get(),
        "collisions" to collisions.get(),
        "warmUpTimestamp" to warmUpCompletionTimestamp,
        "sessionEpoch" to sessionEpoch.get(),
        // Q68-75 (batch 5b): every contributor declares `capabilities`,
        // but nothing ever read them — the collect() comment claims "O(1)
        // capability culling" while actually gating on fastGate, not on
        // capabilities. First reader: per-capability contributor counts,
        // so the dashboard and the crash handler can see which capability
        // classes are actually registered (and which are empty).
        "capabilities" to contributors.flatMap { it.capabilities.toList() }
            .groupingBy { it }.eachCount()
    )

}
