package com.assistant.adapter.input

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * InputThermalEliminatorEngine — HARDWORKING ELIMINATOR for Thermal Throttling.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * When the Helio G81-Ultra heats up, the kernel aggressively caps CPU frequency.
 * Thread priority (URGENT_AUDIO) CANNOT overcome a frequency cap.
 * 
 * This engine polls every 1000ms. If thermal status >= MODERATE, it signals
 * the Queen Bee (SmartAssist) to drop non-essential work and reduce CPU load,
 * allowing the device to cool down and preventing total input lockout.
 */
object InputThermalEliminatorEngine {

    @Volatile private var running = false
    @Volatile var thermalStatus = "UNKNOWN"; private set

    fun start(context: Context) {
        if (running) return
        running = true
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val t = Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            var lastStatus = "UNKNOWN"
            while (running) {
                try {
                    val status = if (Build.VERSION.SDK_INT >= 29 && pm != null) {
                        when (pm.currentThermalStatus) {
                            PowerManager.THERMAL_STATUS_NONE -> "NONE"
                            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                            else -> "UNKNOWN"
                        }
                    } else "UNKNOWN"
                    
                    thermalStatus = status
                    
                    if (status != lastStatus) {
                        lastStatus = status
                        
                        // CONDITIONLESS ACTIVE MITIGATION:
                        if (status == "MODERATE" || status == "SEVERE" || status == "CRITICAL" || status == "EMERGENCY" || status == "SHUTDOWN") {
                            AdapterSignalBus.publishInput("THERMAL_THROTTLE_ACTIVE", status.hashCode().toLong())
                            RuntimeLogger.log("THERMAL THROTTLE DETECTED: $status — Signaling Queen Bee for Survival Mode", "INPUT")
                        } else {
                            AdapterSignalBus.publishInput("THERMAL_THROTTLE_CLEAR", status.hashCode().toLong())
                            RuntimeLogger.log("THERMAL THROTTLE CLEARED: $status — Restoring full performance", "INPUT")
                        }
                    }
                } catch (_: Throwable) {}
                
                try { Thread.sleep(1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-thermal-eliminator"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("InputThermalEliminatorEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }
}
