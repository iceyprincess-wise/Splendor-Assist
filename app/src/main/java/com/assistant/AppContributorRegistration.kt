package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.Executors

/*
 * App-module contributors cannot be registered by RuntimeCoordinator, which
 * lives in adapter_smartassist and must not depend on app. This one-shot,
 * idempotent registrar is invoked from the runtime start path instead.
 *
 * UPGRADE: Heavy initialization and warmUp() are now offloaded to a background
 * thread. This prevents the vision capture loop (running at 15fps on Helio G81)
 * from stalling on the very first frame, protecting the OmnipotentGoalkeeperEngine
 * from missing early-game opponent animations.
 */
object AppContributorRegistration {

    @Volatile private var registered = false
    
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-ContributorWarmup").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            
            warmupExecutor.execute {
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
                    
                    GameplayEngineRegistry.warmAll()
                    
                    RuntimeLogger.log(
                        "AppContributorRegistration: 9 contributors registered and warmed in background " +
                            "(keeper x4 + open-play x3 + attack x2)",
                        "RUNTIME"
                    )
                } catch (e: Throwable) {
                    synchronized(this) { registered = false }
                    RuntimeLogger.log(
                        "AppContributorRegistration failed: ${e.message}",
                        "RUNTIME"
                    )
                }
            }
        }
    }

    fun reset() { registered = false }
}
