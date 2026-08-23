package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class RegistrationState { IDLE, REGISTERING, READY, PARTIAL, FAILED }

/*
 * ARCHITECTURAL DECISION: REDUCED-CONTRIBUTOR FIRST FRAMES
 * We DO NOT block the 15fps vision capture loop to wait for these 37 contributors.
 * On the Helio G81 (Redmi 15C), blocking the first frame for heavy initialization 
 * would cause the OmnipotentGoalkeeperEngine to miss critical early-game opponent 
 * animations. Early frames deliberately run with a reduced contributor set. 
 * As contributors finish warming in this background thread, the CopyOnWriteArrayList 
 * in GameplayEngineRegistry seamlessly integrates them into the live runtime.
 * This is a verified, intentional design trade-off, not a race condition.
 */
object AppContributorRegistration {

    const val ALLOWS_REDUCED_FIRST_FRAMES = true

    private val state = AtomicReference(RegistrationState.IDLE)
    private val generation = AtomicLong(0L)
    private val failedContributors = CopyOnWriteArrayList<String>()
    
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-ContributorWarmup").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun ensureRegistered() {
        val currentState = state.get()
        if (currentState == RegistrationState.READY || currentState == RegistrationState.PARTIAL) return

        synchronized(this) {
            val current = state.get()
            if (current == RegistrationState.READY || current == RegistrationState.PARTIAL || current == RegistrationState.REGISTERING) return
            
            state.set(RegistrationState.REGISTERING)
            val myGeneration = generation.incrementAndGet()
            failedContributors.clear()
            
            warmupExecutor.execute {
                // LIFECYCLE FENCE: Abort immediately if reset() was called
                if (generation.get() != myGeneration) return@execute
                
                try {
                    val initFailures = mutableListOf<String>()
                    
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
                    
                    for (c in allContributors) {
                        if (generation.get() != myGeneration) return@execute // FENCE
                        val success = GameplayEngineRegistry.register(c)
                        if (!success) initFailures.add(c.engineName)
                    }
                    
                    if (generation.get() != myGeneration) return@execute // FENCE
                    
                    val warmFailures = GameplayEngineRegistry.warmAll()
                    
                    if (generation.get() != myGeneration) return@execute // FENCE
                    
                    failedContributors.addAll(initFailures)
                    failedContributors.addAll(warmFailures)
                    
                    val finalState = if (failedContributors.isEmpty()) {
                        RegistrationState.READY
                    } else {
                        RegistrationState.PARTIAL
                    }
                    
                    state.set(finalState)
                    RuntimeLogger.log(
                        "AppContributorRegistration [Gen $myGeneration]: Completed. State=$finalState. " +
                            "Failures=${failedContributors.size} [${failedContributors.joinToString()}]. " +
                            "Registry=${GameplayEngineRegistry.registryRuntimeSnapshot()}",
                        "RUNTIME"
                    )
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
        // Increment generation to act as a hard lifecycle fence, invalidating ANY in-flight task
        generation.incrementAndGet()
        state.set(RegistrationState.IDLE)
        failedContributors.clear()
        // Hard reset the registry to ensure epoch boundary alignment
        GameplayEngineRegistry.resetAll()
    }
    
    fun registrationState(): RegistrationState = state.get()
    fun getFailedContributors(): List<String> = failedContributors.toList()
}
