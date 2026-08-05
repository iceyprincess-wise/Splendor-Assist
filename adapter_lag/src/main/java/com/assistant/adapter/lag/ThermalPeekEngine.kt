package com.assistant.adapter.lag

// V3.1 - thermal evidence probe
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.assistant.diagnostic.RuntimeLogger

/**
 * One cheap system reading: the OS thermal status. If the 7-minute storms
 * correlate with SEVERE+ status, the enemy is throttling and the thermal
 * adapter is the next weapon. If status stays NONE/LIGHT through a storm,
 * throttling is exonerated and scheduler contention becomes prime suspect.
 */
object ThermalPeekEngine {

    @Volatile private var pm: PowerManager? = null
    @Volatile private var running = false
    @Volatile var status = "?"; private set

    fun init(ctx: Context) {
        if (running) return
        running = true
        pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val t = Thread {
            var last = ""
            while (running) {
                try {
                    val s = if (Build.VERSION.SDK_INT >= 29) {
                        when (pm?.currentThermalStatus ?: -1) {
                            PowerManager.THERMAL_STATUS_NONE -> "NONE"
                            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                            else -> "?"
                        }
                    } else "UNSUPPORTED"
                    status = s
                    if (s != last) {
                        last = s
                        RuntimeLogger.log("THERMAL STATUS -> " + s, "LAGTHERM")
                    }
                } catch (_: Throwable) { }
                try { Thread.sleep(10_000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-thermal-peek"; t.start()
    }

    fun stop() { running = false }
}
