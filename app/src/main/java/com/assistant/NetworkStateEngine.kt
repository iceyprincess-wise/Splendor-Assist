package com.assistant







import android.content.Context







import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities


















import com.assistant.diagnostic.RuntimeLogger






























object NetworkStateEngine {

    // No68-E1-D26: this was labelled "ADMIN-TUNABLE" but the getter
    // returned a hard-coded 10000L and read no admin store anywhere —
    // the same false label the other engines carry. It is a plain
    // constant until a real admin knob exists; do not claim otherwise.
    private const val POLL_MS: Long = 10000L

    @Volatile var transport: String = "NONE"
        private set
    @Volatile private var running = false
    @Volatile private var last = ""
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    // No68-E1-D27: the poll thread was never kept, so stop() could not wake
    // it — it slept out its 10s, and a restart in that window left TWO
    // poll threads applying transport changes concurrently.
    @Volatile private var pollThread: Thread? = null

    fun start(ctx: Context) {
        if (running) return
        // No68-E1-D27c: a previous poll thread still sleeping out its 10s
        // after stop() must be dead before a new one starts.
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
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
                    // No68-E1-D28: getNetworkCapabilities(activeNetwork) can
                    // return null transiently (network flipping, radio
                    // renegotiating). The old code applied that null as a
                    // real "NONE", wiping NetProbe's RTT history and
                    // re-warming DNS on every transient blip, up to once
                    // per poll. A null poll reading is "no reading" — keep
                    // the last transport. A real loss still arrives via
                    // the callback's onLost(), which applies NONE at once.
                    val caps = mgr.getNetworkCapabilities(mgr.activeNetwork)
                    if (caps != null) apply(app, classify(caps))
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        th.isDaemon = true; th.name = "net-state"; pollThread = th; th.start()
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
        // No68-E1-D27b: wake the poll thread so it exits now; the
        // reference is kept so a quick restart can join the old thread
        // instead of running two pollers (see start()).
        pollThread?.interrupt()
    }
}

/* ========
PacketLossProbeEngine
======== */
// V3 INSTANT-REFLEX

/**
 * Measures PACKET LOSS - the metric TCP probes cannot see. Sends real UDP DNS
 * queries and counts replies. V3: queries alternate between two independent
 * resolvers (8.8.8.8 / 1.1.1.1) so a single resolver hiccup no longer reads
 * as link loss; the gap between queries is admin-tunable; live loss is
 * published for the admin Detector.
 */
