package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayEngineRegistry

/*
 * App-module contributors cannot be registered by RuntimeCoordinator, which
 * lives in adapter_smartassist and must not depend on app. This one-shot,
 * idempotent registrar is invoked from the runtime start path instead.
 *
 * Task C item (d): full family now onboarded.
 *  - keeper family x4 (ThreatPriority, CrossClaim, KeeperBias, PanicSave)
 *  - open-play family x3 (PassLane, BallPress, PressEvade)
 *  - attack family x2 (Shot, CrossDelivery) - onboarded only AFTER the
 *    frame was extended to carry the goal detector's real output, so
 *    neither ever aims at a fabricated coordinate.
 * All eight ActionClass values except MOVE/NONE now have a real owner.
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
                GameplayEngineRegistry.register(
                    com.assistant.contributors.PassLaneContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.BallPressContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.PressEvadeContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.ShotContributor)
                GameplayEngineRegistry.register(
                    com.assistant.contributors.CrossDeliveryContributor)
                GameplayEngineRegistry.warmAll()
                RuntimeLogger.log(
                    "AppContributorRegistration: 9 contributors registered " +
                        "(keeper x4 + open-play x3 + attack x2)",
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
