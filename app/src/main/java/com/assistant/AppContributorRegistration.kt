package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayEngineRegistry

/*
 * App-module contributors cannot be registered by RuntimeCoordinator, which
 * lives in adapter_smartassist and must not depend on app. This one-shot,
 * idempotent registrar is invoked from the runtime start path instead.
 *
 * Task C item (d): open-play family added (PassLane, BallPress, PressEvade).
 * With the keeper family this covers KEEPER, DEFENSE, PASSING and MOVEMENT
 * capabilities. SHOT and CROSS are deliberately NOT onboarded yet: the
 * RuntimeFrame carries no goal-frame coordinates, and a shot contributor
 * without a real goal target would have to fabricate one - the exact kind
 * of fake precision this branch removes. The honest path is extending
 * FrameAssembler to carry the goal detector's output first; queued next.
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
                GameplayEngineRegistry.warmAll()
                RuntimeLogger.log(
                    "AppContributorRegistration: 7 contributors registered " +
                        "(keeper family x4 + open-play family x3)",
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
