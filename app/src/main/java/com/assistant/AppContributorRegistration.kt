package com.assistant

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
