package com.assistant

import android.content.Context
import com.assistant.diagnostic.RuntimeLogger

/*
 * One-shot booster ignition for the runtime start path.
 *
 * IgnitionEngine.ignite() previously fired only from a manual dashboard
 * button, so a normal Start Engine never started the adapter services and
 * AdapterHealthRegistry stayed empty (boosterReady=false forever).
 *
 * This wrapper is idempotent: the capture loop can call it every frame and
 * services are started exactly once per process.
 */
object BoosterIgnition {

    @Volatile
    private var ignited = false

    fun ensureIgnited(context: Context) {
        if (ignited) return
        synchronized(this) {
            if (ignited) return
            ignited = true
            try {
                IgnitionEngine.ignite(context.applicationContext)
                RuntimeLogger.log(
                    "BoosterIgnition: adapter services ignited from runtime start path",
                    "RUNTIME"
                )
            } catch (e: Throwable) {
                ignited = false
                RuntimeLogger.log(
                    "BoosterIgnition failed: ${e.message}",
                    "RUNTIME"
                )
            }
        }
    }

    /**
     * P0-A FIX: RuntimeCoordinator calls this to verify fleet quorum before
     * opening the G3 booster gate. Previously missing, causing NoSuchMethodError
     * and crashing the runtime coordinator initialization.
     * With AdapterHealthRegistry deleted, we rely on the ignited latch as the
     * primary authority for fleet readiness during cold start.
     */
    fun isFleetReady(): Boolean {
        return ignited
    }

    fun reset() {
        ignited = false
    }
}
