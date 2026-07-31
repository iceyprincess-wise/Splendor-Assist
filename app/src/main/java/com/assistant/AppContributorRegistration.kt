package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayEngineRegistry

/*
 * App-module contributors cannot be registered by RuntimeCoordinator, which
 * lives in adapter_smartassist and must not depend on app. This one-shot,
 * idempotent registrar is invoked from the runtime start path instead.
 */
object AppContributorRegistration {

    @Volatile private var registered = false

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            try {
                GameplayEngineRegistry.register(
                    com.assistant.contributors.ThreatPriorityContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.CrossClaimContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.KeeperBiasContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.PanicSaveContributor)
                GameplayEngineRegistry.warmAll()
                RuntimeLogger.log(
                    "AppContributorRegistration: 4 keeper-family contributors registered",
                    "RUNTIME"
                )
            } catch (e: Throwable) {
                registered = false
                RuntimeLogger.log(
                    "AppContributorRegistration failed: ${e.message}",
                    "RUNTIME"
                )
            }
        }
    }

    fun reset() { registered = false }
}
