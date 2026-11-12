package com.assistant







import android.content.Context













import android.os.Build







import android.os.PowerManager






import com.assistant.diagnostic.RuntimeLogger






























object ThermalPeekEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val POLL_MS: Long get() = 10_000L

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
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-thermal-peek"; t.start()
    }

    fun stop() { running = false }
}

// --- NEW: GcStallEngine ---
