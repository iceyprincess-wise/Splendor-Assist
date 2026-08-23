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
 * 
 * FIX: Ignition now returns a real success/failure result. ignited=true is
 * ONLY set after the gate (ComplianceState) is actually satisfied and 
 * IgnitionEngine returns true, preventing a false-success latch where
 * services fail to start permanently.
 */
object BoosterIgnition {

    @Volatile
    private var ignited = false

    fun ensureIgnited(context: Context) {
        if (ignited) return
        synchronized(this) {
            if (ignited) return
            
            // FIX: Capture the real success/failure result from the engine.
            val success = IgnitionEngine.ignite(context.applicationContext)
            
            if (success) {
                ignited = true
                RuntimeLogger.log(
                    "BoosterIgnition: adapter services ignited from runtime start path",
                    "RUNTIME"
                )
            } else {
                RuntimeLogger.log(
                    "BoosterIgnition: gate not satisfied, retrying later",
                    "RUNTIME"
                )
            }
        }
    }

    fun reset() {
        ignited = false
    }
}
