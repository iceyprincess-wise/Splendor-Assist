package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class RegistrationState { IDLE, REGISTERING, READY, PARTIAL, FAILED }

object AppContributorRegistration {

    const val ALLOWS_REDUCED_FIRST_FRAMES = true
    const val EXPECTED_CONTRIBUTOR_COUNT = 37

    // HIGH: Completeness invariant - explicit set of expected names
    private val EXPECTED_CONTRIBUTOR_NAMES = setOf(
        "ThreatPriority", "CrossClaim", "KeeperBias", "PanicSave", "PassLane", "BallPress", "PressEvade", "Shot", "CrossDelivery",
        "MagneticFeet", "Passing", "Support", "Defense", "Evade", "AttackingVector", "Cross", "Agility", "WingBlock",
        "DashPressure", "InterceptMatrix", "TouchRecovery", "OverloadPlaystyle", "TruePass", "ReceiverEngagement", "ForwardRun",
        "ShotOpportunity", "DefenseAuthority", "ShotAnticipation", "KeeperFeedback", "DashAnchor", "SpeedCompensation",
        "InstantIntercept", "BuildUpPress", "BallRetentionShield", "TrueShot", "TrueCross", "SmartAssistUltimateCorrector"
    )

    private val state = AtomicReference(RegistrationState.IDLE)
    private val generation = AtomicLong(0L)
    
    // Thread-safe lists for runtime proof telemetry
    private val initFailures = CopyOnWriteArrayList<String>()
    private val warmFailures = CopyOnWriteArrayList<String>()
    private val omittedContributors = CopyOnWriteArrayList<String>()
    
    // MEDIUM: Store actual Future for hard cancellation
    @Volatile private var currentFuture: Future<*>? = null

    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-ContributorWarmup").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun ensureRegistered() {
        val currentState = state.get()
        // HIGH: Recovery semantics - allow retry from PARTIAL or FAILED
        if (currentState == RegistrationState.READY) return
        if (currentState == RegistrationState.REGISTERING) return

        synchronized(this) {
            val current = state.get()
            if (current == RegistrationState.READY || current == RegistrationState.REGISTERING) return
            
            state.set(RegistrationState.REGISTERING)
            val myGeneration = generation.incrementAndGet()
            initFailures.clear()
            warmFailures.clear()
            omittedContributors.clear()
            
            // MEDIUM: Restart behavior - if retrying from PARTIAL/FAILED, clear registry for clean slate
            if (current == RegistrationState.PARTIAL || current == RegistrationState.FAILED) {
                GameplayEngineRegistry.resetAll()
            }
            
            currentFuture = warmupExecutor.submit {
                if (generation.get() != myGeneration) return@submit
                
                try {
                    val allContributors = listOf<GameplayContributor>(
                        com.assistant.contributors.ThreatPriorityContributor,
                        com.assistant.contributors.CrossClaimContributor,
                        com.assistant.contributors.KeeperBiasContributor,
                        com.assistant.contributors.PanicSaveContributor,
                        com.assistant.contributors.PassLaneContributor,
                        com.assistant.contributors.BallPressContributor,
                        com.assistant.contributors.PressEvadeContributor,
                        com.assistant.contributors.ShotContributor,
                        com.assistant.contributors.CrossDeliveryContributor,
                        
                        com.assistant.adapter.smartassist.contributors.MagneticFeetContributor,
                        com.assistant.adapter.smartassist.contributors.PassingContributor,
                        com.assistant.adapter.smartassist.contributors.SupportContributor,
                        com.assistant.adapter.smartassist.contributors.DefenseContributor,
                        com.assistant.adapter.smartassist.contributors.EvadeContributor,
                        com.assistant.adapter.smartassist.contributors.AttackingVectorContributor,
                        com.assistant.adapter.smartassist.contributors.CrossContributor,
                        com.assistant.adapter.smartassist.contributors.AgilityContributor,
                        com.assistant.adapter.smartassist.contributors.WingBlockContributor,
                        com.assistant.adapter.smartassist.contributors.DashPressureContributor,
                        com.assistant.adapter.smartassist.contributors.InterceptMatrixContributor,
                        com.assistant.adapter.smartassist.contributors.TouchRecoveryContributor,
                        com.assistant.adapter.smartassist.contributors.OverloadPlaystyleContributor,
                        com.assistant.adapter.smartassist.contributors.TruePassContributor,
                        com.assistant.adapter.smartassist.contributors.ReceiverEngagementContributor,
                        com.assistant.adapter.smartassist.contributors.ForwardRunContributor,
                        com.assistant.adapter.smartassist.contributors.ShotOpportunityContributor,
                        com.assistant.adapter.smartassist.contributors.DefenseAuthorityContributor,
                        com.assistant.adapter.smartassist.contributors.ShotAnticipationContributor,
                        com.assistant.adapter.smartassist.contributors.KeeperFeedbackContributor,
                        com.assistant.adapter.smartassist.contributors.DashAnchorContributor,
                        com.assistant.adapter.smartassist.contributors.SpeedCompensationContributor,
                        com.assistant.adapter.smartassist.contributors.InstantInterceptContributor,
                        com.assistant.adapter.smartassist.contributors.BuildUpPressContributor,
                        com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor,
                        com.assistant.adapter.smartassist.contributors.TrueShotContributor,
                        com.assistant.adapter.smartassist.contributors.TrueCrossContributor,
                        com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor
                    )
                    
                    // MEDIUM: Runtime assertion for 37-contributor contract
                    check(allContributors.size == EXPECTED_CONTRIBUTOR_COUNT) {
                        "CRITICAL: Hardcoded contributor list size (${allContributors.size}) != expected ($EXPECTED_CONTRIBUTOR_COUNT)"
                    }
                    
                    for (c in allContributors) {
                        if (generation.get() != myGeneration) return@submit
                        val success = GameplayEngineRegistry.register(c)
                        if (!success) initFailures.add(c.engineName)
                    }
                    
                    if (generation.get() != myGeneration) return@submit
                    
                    val warmFails = GameplayEngineRegistry.warmAll()
                    warmFailures.addAll(warmFails)
                    
                    if (generation.get() != myGeneration) return@submit
                    
                    // HIGH: Distinguish omission from failure
                    val actualNames = GameplayEngineRegistry.getRegisteredNames()
                    val missing = EXPECTED_CONTRIBUTOR_NAMES - actualNames
                    omittedContributors.addAll(missing)
                    
                    val finalState = when {
                        missing.isNotEmpty() || initFailures.isNotEmpty() || warmFailures.isNotEmpty() -> RegistrationState.PARTIAL
                        else -> RegistrationState.READY
                    }
                    
                    state.set(finalState)
                    RuntimeLogger.log(dumpRuntimeProof(myGeneration), "RUNTIME")
                    
                } catch (e: Throwable) {
                    if (generation.get() == myGeneration) {
                        state.set(RegistrationState.FAILED)
                        RuntimeLogger.log("AppContributorRegistration [Gen $myGeneration] FAILED: ${e.message}", "RUNTIME")
                    }
                }
            }
        }
    }

    fun reset() {
        generation.incrementAndGet()
        currentFuture?.cancel(true) // MEDIUM: Hard cancellation via Future interrupt
        currentFuture = null
        state.set(RegistrationState.IDLE)
        GameplayEngineRegistry.resetAll()
    }
    
    // MEDIUM: Explicit restart behavior for PARTIAL/FAILED
    fun retry() {
        if (state.get() == RegistrationState.PARTIAL || state.get() == RegistrationState.FAILED) {
            state.set(RegistrationState.IDLE)
            ensureRegistered()
        }
    }
    
    // LOW: Live runtime proof
    fun dumpRuntimeProof(gen: Long = generation.get()): String {
        val actualCount = GameplayEngineRegistry.getRegisteredNames().size
        return """
            |=== REGISTRATION RUNTIME PROOF [Gen $gen] ===
            |State: ${state.get()}
            |Expected Count: $EXPECTED_CONTRIBUTOR_COUNT | Actual Count: $actualCount
            |Init Failures: ${initFailures.size} ${if(initFailures.isNotEmpty()) "[${initFailures.joinToString()}]" else ""}
            |Warm Failures: ${warmFailures.size} ${if(warmFailures.isNotEmpty()) "[${warmFailures.joinToString()}]" else ""}
            |Omitted/Missing: ${omittedContributors.size} ${if(omittedContributors.isNotEmpty()) "[${omittedContributors.joinToString()}]" else ""}
            |Registry Snapshot: ${GameplayEngineRegistry.registryRuntimeSnapshot()}
            |=============================================
        """.trimMargin()
    }
    
    fun registrationState(): RegistrationState = state.get()
}
