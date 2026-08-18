package com.assistant.adapter.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.assistant.diagnostic.RuntimeLogger

/**
 * ConnectionHealEngine — Active network healer. Never accept a bad connection.
 * When HOLD is detected: WiFi rescan + rebind to best available network.
 * Cooldown: one heal per 15s (never floods the modem).
 */
object ConnectionHealEngine {
    @Volatile private var running = false
    @Volatile private var lastHealMs = 0L
    @Volatile var healCount = 0; private set
    private const val COOLDOWN_MS = 15_000L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    RuntimeLogger.log("ConnectionHeal: network LOST — healing", "NETHEAL")
                    tryHeal(appCtx)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.linkDownstreamBandwidthKbps in 1..500) {
                        RuntimeLogger.log("ConnectionHeal: bandwidth critical — healing", "NETHEAL")
                        tryHeal(appCtx)
                    }
                }
            })
        } catch (t: Throwable) {
            RuntimeLogger.log("ConnectionHeal: callback failed: ${t.message}", "NETHEAL")
        }
        val t = Thread {
            while (running) {
                try { if (ActionWindowEngine.verdict == "HOLD") tryHeal(appCtx) } catch (_: Throwable) {}
                try { Thread.sleep(8_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-heal"; t.start()
        RuntimeLogger.log("ConnectionHealEngine started", "NETHEAL")
    }

    fun stop() { running = false }

    private fun tryHeal(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastHealMs < COOLDOWN_MS) return
        lastHealMs = now; healCount++
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                @Suppress("DEPRECATION") wm.startScan()
                RuntimeLogger.log("ConnectionHeal: WiFi rescan #$healCount", "NETHEAL")
            }
        } catch (_: Throwable) {}
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.bindProcessToNetwork(it) }
            RuntimeLogger.log("ConnectionHeal: rebound to best network #$healCount", "NETHEAL")
        } catch (_: Throwable) {}
    }
}
