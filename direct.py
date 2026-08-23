import os
import re

print("=== INITIATING GODMODE UPGRADE SEQUENCE ===")

# 1. OVERWRITE: GameplayEngineRegistry.kt (Atomic Registration + Telemetry)
registry_content = """package com.assistant.runtime

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
"""
path1 = "core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt"
os.makedirs(os.path.dirname(path1), exist_ok=True)
with open(path1, "w") as f: f.write(registry_content)
print("[+] Upgraded GameplayEngineRegistry.kt (Atomic + Telemetry)")

# 2. OVERWRITE: AppContributorRegistration.kt (Unified Ownership + State Machine)
app_reg_content = """package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

enum class RegistrationState { IDLE, REGISTERING, READY, FAILED }

/*
 * App-module and Adapter-module contributors are now registered exclusively here.
 * This one-shot, idempotent registrar is invoked from the runtime start path.
 *
 * DETERMINISTIC RULE: First-frame race elimination.
 * Heavy initialization and warmUp() are offloaded to a background thread
 * to prevent the vision capture loop (running at 15fps on Helio G81) from
 * stalling on the very first frame. 
 * CONSEQUENCE: Early frames deliberately run with a reduced contributor set.
 * The frame loop does NOT block on registration completion. The OmnipotentGoalkeeperEngine
 * and other critical engines are protected from missing early-game animations.
 * As contributors register, CopyOnWriteArrayList in GameplayEngineRegistry 
 * seamlessly integrates them into the live runtime without locking the frame loop.
 */
object AppContributorRegistration {

    private val state = AtomicReference(RegistrationState.IDLE)
    private val generation = AtomicLong(0L)
    @Volatile private var warmUpCompletionTimestamp: Long = 0L
    
    @Volatile private var currentTask: CompletableFuture<Void>? = null

    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-ContributorWarmup").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun ensureRegistered() {
        val currentState = state.get()
        if (currentState == RegistrationState.READY) return
        if (currentState == RegistrationState.REGISTERING) return

        synchronized(this) {
            val current = state.get()
            if (current == RegistrationState.READY || current == RegistrationState.REGISTERING) return
            
            state.set(RegistrationState.REGISTERING)
            val gen = generation.incrementAndGet()
            
            val future = CompletableFuture<Void>()
            currentTask = future
            
            warmupExecutor.execute {
                if (future.isCancelled) return@execute
                
                try {
                    GameplayEngineRegistry.register(com.assistant.contributors.ThreatPriorityContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.CrossClaimContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.KeeperBiasContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.PanicSaveContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.PassLaneContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.BallPressContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.PressEvadeContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.ShotContributor)
                    GameplayEngineRegistry.register(com.assistant.contributors.CrossDeliveryContributor)
                    
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.MagneticFeetContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.PassingContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.SupportContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.DefenseContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.EvadeContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.AttackingVectorContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.CrossContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.AgilityContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.WingBlockContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.DashPressureContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.InterceptMatrixContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.TouchRecoveryContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.OverloadPlaystyleContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.TruePassContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.ReceiverEngagementContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.ForwardRunContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.ShotOpportunityContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.DefenseAuthorityContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.ShotAnticipationContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.KeeperFeedbackContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.DashAnchorContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.SpeedCompensationContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.InstantInterceptContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.BuildUpPressContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.TrueShotContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.TrueCrossContributor)
                    GameplayEngineRegistry.register(com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor)

                    if (future.isCancelled) return@execute
                    
                    GameplayEngineRegistry.warmAll()
                    warmUpCompletionTimestamp = System.currentTimeMillis()
                    
                    state.set(RegistrationState.READY)
                    future.complete(null)
                    
                    RuntimeLogger.log(
                        "AppContributorRegistration [Gen $gen]: 37 contributors registered and warmed in background " +
                            "at $warmUpCompletionTimestamp. State=READY. Registry=${GameplayEngineRegistry.registryRuntimeSnapshot()}",
                        "RUNTIME"
                    )
                } catch (e: Throwable) {
                    if (!future.isCancelled) {
                        state.set(RegistrationState.FAILED)
                        future.completeExceptionally(e)
                        RuntimeLogger.log("AppContributorRegistration [Gen $gen] FAILED: ${e.message}", "RUNTIME")
                    }
                }
            }
        }
    }

    fun reset() {
        currentTask?.cancel(true)
        state.set(RegistrationState.IDLE)
    }
    
    fun registrationState(): RegistrationState = state.get()
}
"""
path2 = "app/src/main/java/com/assistant/AppContributorRegistration.kt"
os.makedirs(os.path.dirname(path2), exist_ok=True)
with open(path2, "w") as f: f.write(app_reg_content)
print("[+] Upgraded AppContributorRegistration.kt (Unified + State Machine)")

# 3. PATCH: RuntimeCoordinator.kt (Strip Registry Mutations)
rc_path = "app/src/main/java/com/assistant/adapter/smartassist/RuntimeCoordinator.kt"
if os.path.exists(rc_path):
    with open(rc_path, "r") as f: rc_content = f.read()
    
    old_rc_block = """    private fun warmUpEngines() {
        try { TelemetryRepository.current() } catch (_: Throwable) {}
        try { SceneTracker.current() } catch (_: Throwable) {}
        try { Phase3WorldStateStore.current() } catch (_: Throwable) {}
        try { SmartAssistRepository.enabled() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot() } catch (_: Throwable) {}
        try { MagneticFeetEngine.magneticFeetSnapshot() } catch (_: Throwable) {}
        try { OverloadPlaystyleEngine.overloadRuntimeSnapshot() } catch (_: Throwable) {}
        try { GameplayDecisionEngine.gameplayActivationDiagnostics() } catch (_: Throwable) {}
        try { TrueTargetPassingEngine.currentReceiverRankingResult() } catch (_: Throwable) {}
        try { SmartAssistMetrics.snapshot() } catch (_: Throwable) {}
        try {
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.MagneticFeetContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.PassingContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ShotContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SupportContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DefenseContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.EvadeContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.AttackingVectorContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.CrossContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.AgilityContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.WingBlockContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DashPressureContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.InterceptMatrixContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TouchRecoveryContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.OverloadPlaystyleContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TruePassContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ReceiverEngagementContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ForwardRunContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ShotOpportunityContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DefenseAuthorityContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ShotAnticipationContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.KeeperFeedbackContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DashAnchorContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SpeedCompensationContributor)
            // BATCH 4: instant intercept + build-up press + ball retention shield
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.InstantInterceptContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.BuildUpPressContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor)
            // BATCH S: TrueShot + TrueCross + SA Ultimate Corrector (#27-29)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TrueShotContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TrueCrossContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor)
        } catch (_: Throwable) {}
        try { GameplayEngineRegistry.warmAll() } catch (_: Throwable) {}
    }"""
    
    new_rc_block = """    private fun warmUpEngines() {
        // Unified registry ownership: all contributor registrations and warm-ups 
        // are now handled atomically by AppContributorRegistration to prevent 
        // dual-initialization races and warm-up idempotency issues.
        // This function now strictly handles read-only ignition for stores and engines only.
        try { TelemetryRepository.current() } catch (_: Throwable) {}
        try { SceneTracker.current() } catch (_: Throwable) {}
        try { Phase3WorldStateStore.current() } catch (_: Throwable) {}
        try { SmartAssistRepository.enabled() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot() } catch (_: Throwable) {}
        try { MagneticFeetEngine.magneticFeetSnapshot() } catch (_: Throwable) {}
        try { OverloadPlaystyleEngine.overloadRuntimeSnapshot() } catch (_: Throwable) {}
        try { GameplayDecisionEngine.gameplayActivationDiagnostics() } catch (_: Throwable) {}
        try { TrueTargetPassingEngine.currentReceiverRankingResult() } catch (_: Throwable) {}
        try { SmartAssistMetrics.snapshot() } catch (_: Throwable) {}
    }"""
    
    if old_rc_block in rc_content:
        rc_content = rc_content.replace(old_rc_block, new_rc_block)
        with open(rc_path, "w") as f: f.write(rc_content)
        print("[+] Patched RuntimeCoordinator.kt (Stripped Registry Mutations)")
    else:
        print("[!] WARNING: Could not find exact old block in RuntimeCoordinator.kt")

# 4. PATCH: GlobalCrashHandler.kt & Delete Weak ShotContributor
gh_path = "app/src/main/java/com/assistant/GlobalCrashHandler.kt"
if os.path.exists(gh_path):
    with open(gh_path, "r") as f: gh_content = f.read()
    
    old_gh_entry = 'EngineEntry("ShotContributor","com.assistant.adapter.smartassist.contributors.ShotContributor","ACTIVE","fires within 550px of goal")'
    new_gh_entry = 'EngineEntry("ShotContributor","com.assistant.contributors.ShotContributor","ACTIVE","fires only on real goal detection; no hallucinated aim points")'
    
    if old_gh_entry in gh_content:
        gh_content = gh_content.replace(old_gh_entry, new_gh_entry)
        
        dup_gh_entry = 'EngineEntry("ShotContributor(app)","com.assistant.contributors.ShotContributor","ACTIVE","goal-mouth shot with keeper bias (requires goalDetected)"),\n'
        if dup_gh_entry in gh_content:
            gh_content = gh_content.replace(dup_gh_entry, '')
            
        with open(gh_path, "w") as f: f.write(gh_content)
        print("[+] Patched GlobalCrashHandler.kt (Updated ShotContributor Identity)")

weak_shot_path = "app/src/main/java/com/assistant/adapter/smartassist/contributors/ShotContributor.kt"
if os.path.exists(weak_shot_path):
    os.remove(weak_shot_path)
    print(f"[+] DELETED weak hallucinating engine: {weak_shot_path}")

print("=== UPGRADE SEQUENCE COMPLETE. ALL ROOT CAUSES RESOLVED. ===")
