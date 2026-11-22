package com.assistant







import android.content.Context







import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
















import com.assistant.diagnostic.RuntimeLogger






























object ConnectionHealEngine {
    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    @Volatile private var lastHealMs = 0L
    @Volatile var healCount = 0; private set
    private const val COOLDOWN_MS = 15_000L

    // Batch booster: the registered callback + manager are kept so
    // stop() can unregister them. Before this fix stop() only cleared
    // `running`, so the callback could still fire tryHeal() after the
    // service was destroyed (the poll thread exits on its own sleep, but
    // the callback had no teardown at all — unlike NetworkStateEngine,
    // whose stop() unregisters).
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        if (running) return; running = true
        // Task 5 (gate): D18 double-start fix, R-1/R-3 shape (GcStall T-14).
        // stop() used to be bare `running = false` with no stored thread
        // and no interrupt: a restart inside the poll sleep left the old
        // poller alive beside the new one (both looping forever, since the
        // old thread wakes to running == true again). Join the old thread
        // 500 ms and give up - with running left true the old poller keeps
        // the engine alive on its own, so start() is a safe no-op.
        val old = pollThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        val appCtx = ctx.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
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
            }
            cm.registerNetworkCallback(req, cb)
            connectivityManager = cm
            networkCallback = cb
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
        pollThread = t
        RuntimeLogger.log("ConnectionHealEngine started", "NETHEAL")
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        val cm = connectivityManager
        val cb = networkCallback
        connectivityManager = null
        networkCallback = null
        if (cm != null && cb != null) {
            try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
    }

    // Item 16 Letter C (B1): first live reader for the heal count —
    // GameplayRoomActivity displays it on the diagnostics screen.
    // Before this it was read only by this file's own log lines.
    fun diagnostics(): Map<String, Any> = mapOf(
        "healCount" to healCount,
        "running" to running
    )

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

/* ========
DnsWarmupEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V3: warmNow() lets the network-state engine force an immediate re-warm the
 * second the link changes - server addresses are hot on the NEW network
 * within a second instead of waiting out the rewarm interval.
 */
