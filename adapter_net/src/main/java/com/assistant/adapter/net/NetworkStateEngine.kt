package com.assistant.adapter.net

// V3 INSTANT-REFLEX
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.assistant.diagnostic.RuntimeLogger

/**
 * V3: the OS now TELLS us the instant the network changes (default network
 * callback) instead of us noticing up to 10s late on a poll. On every change
 * the carrier profile re-selects, the probe engine wipes history and
 * re-probes immediately, and DNS re-warms - the whole stack re-learns the
 * new link in about one second. The poll remains only as a belt-and-braces
 * fallback and its cadence is admin-tunable.
 */
object NetworkStateEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val POLL_MS: Long get() = 10000L

    @Volatile var transport: String = "NONE"
        private set
    @Volatile private var running = false
    @Volatile private var last = ""
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm = mgr
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    apply(app, classify(caps))
                }
                override fun onLost(network: Network) { apply(app, "NONE") }
            }
            mgr.registerDefaultNetworkCallback(cb)
            callback = cb
            RuntimeLogger.log("instant network-change callback registered", "NET")
        } catch (t: Throwable) {
            RuntimeLogger.log("network callback unavailable, poll only: " + t.message, "NET")
        }
        val th = Thread {
            while (running) {
                try {
                    val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    apply(app, classify(mgr.getNetworkCapabilities(mgr.activeNetwork)))
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        th.isDaemon = true; th.name = "net-state"; th.start()
    }

    private fun classify(caps: NetworkCapabilities?): String = when {
        caps == null -> "NONE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        else -> "OTHER"
    }

    @Synchronized
    private fun apply(app: Context, now: String) {
        transport = now
        if (now == last) return
        last = now
        if (now == "WIFI") CarrierProfileEngine.useWifiProfile()
        else CarrierProfileEngine.detect(app)
        NetProbeEngine.onLinkChanged()
        DnsWarmupEngine.warmNow()
        RuntimeLogger.log("Transport -> " + now + " (stack re-learning link now)", "NET")
    }

    fun stop() {
        running = false
        try { callback?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Throwable) { }
        callback = null
    }
}
