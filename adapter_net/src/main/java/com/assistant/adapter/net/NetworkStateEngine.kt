package com.assistant.adapter.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.assistant.diagnostic.RuntimeLogger

/** Watches the live transport (cellular/wifi) and re-selects the carrier profile on change. */
object NetworkStateEngine {

    @Volatile var transport: String = "NONE"
        private set
    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        val t = Thread {
            var last = ""
            while (running) {
                try {
                    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    val now = when {
                        caps == null -> "NONE"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                        else -> "OTHER"
                    }
                    transport = now
                    if (now != last) {
                        last = now
                        if (now == "WIFI") CarrierProfileEngine.useWifiProfile()
                        else CarrierProfileEngine.detect(app)
                        RuntimeLogger.log("Transport -> " + now, "NET")
                    }
                } catch (_: Throwable) { }
                try { Thread.sleep(10000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-state"; t.start()
    }

    fun stop() { running = false }
}
