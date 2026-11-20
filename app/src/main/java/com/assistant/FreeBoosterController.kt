// File-level DEPRECATION suppress: the PhoneStateListener path below is
// the ONLY signal-strength API on API 26-30, and the app's minSdk is 26,
// so the deprecated pre-31 listener is deliberately kept (API 31+ uses
// TelephonyCallback.SignalStrengthsListener). The import, the field, and
// the override inside the anonymous listener would each warn otherwise.
@file:Suppress("DEPRECATION")
package com.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.CellSignalStrength
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.assistant.diagnostic.LogMirror
import com.assistant.diagnostic.RuntimeLogger

/**
 * Free booster engine — queue item 4 (Ed 2026-11-20 ~15:10 WAT).
 * ANDROID GLUE. Started/stopped by PerformanceEngineService (ignite in
 * onCreate, stop in onDestroy — same pattern as the other network
 * engines). All decisions live in the pure FreeBoosterEngine; this file
 * only gathers live readings, applies the decision to real Android APIs,
 * and logs the before/after readings.
 *
 * WHAT IT DOES (each with a live consumer):
 *  1. Match-mode radio warm — drives RadioKeepAliveEngine.setMatchBoost
 *     so the keepalive runs at its 4 s floor during a match.
 *  2. Transport preference request — on match start asks the system for
 *     a network of the CURRENT transport (Wi-Fi stays Wi-Fi, cellular
 *     stays cellular) for THIS app's process, and learns about switches
 *     instantly through the callback instead of the 10 s poll. This is a
 *     REQUEST, not a hold: the system may still move the default network,
 *     hand over between towers, or fall back between transports, and
 *     nothing here can pin the game app's transport or stop a tower
 *     switch. The honest value is instant switch detection plus an
 *     immediate re-probe (NetProbeEngine.onLinkChanged). Requesting the
 *     OTHER transport would try to move the phone — forbidden by design,
 *     so the request always matches the transport already in use.
 *  3. Real signal reading — level 0..4 + dBm from the telephony stack,
 *     scoped to the data subscription. Gated on READ_PHONE_STATE being
 *     granted: without the grant the values stay unknown (-1 / 0) and are
 *     NEVER invented. One log line names the Settings path when the grant
 *     is missing.
 *  4. Shed the booster's OWN work when degraded — drops queued routine
 *     log lines (crash-waiter lines are kept by construction) and pauses
 *     the DNS warmup thread until the link recovers. Gameplay engines are
 *     never touched by this path.
 *  5. Screen keep-awake — a SCREEN_BRIGHT_WAKE_LOCK with a 10-minute
 *     timeout, held only while a match is on. The overlay window's
 *     keep-screen-on flag is UNVERIFIED on overlay windows, so this lock
 *     is the booster's own keep-awake path; the PARTIAL locks elsewhere
 *     keep the CPU only, not the screen. The timeout bounds the worst
 *     case if a match-end edge is ever missed.
 *
 * WHAT IT NEVER DOES: no paid relay, no server component, no routing of
 * another app's traffic, no throttling/gating/suppressing of any gameplay
 * engine, no capture-interval change, no deferral of the synchronous
 * death-window log. RAM pressure is owned by MemoryMonitorEngine +
 * NonEssentialCacheEvictor + MemoryCaptureGateEngine (D1: capture never
 * slows in-game) — this controller adds no second memory path.
 *
 * Match signal: VisionTrust.isGameForeground() (game package foreground +
 * own-UI veto, already debounced 2 s). The controller synthesises
 * start/stop edges from it via FreeBoosterEngine.onPoll() — no per-match
 * hook exists anywhere in the repo to attach to instead.
 */
object FreeBoosterController {

    private const val POLL_MS = 5_000L
    private const val SHED_COOLDOWN_MS = 30_000L
    private const val SNAPSHOT_EVERY_POLLS = 6 // 30 s in-match readings
    private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
    private const val PHONE_PERMISSION_REQUEST_NOTE =
        "BOOSTER signal unknown: READ_PHONE_STATE not granted — grant Phone permission for Splendor Assist in Settings to enable real bars/dBm readings"

    @Volatile private var running = false
    @Volatile private var lastShedMs = 0L
    @Volatile private var signalGrantLogged = false
    private var appCtx: Context? = null
    private var pollThread: Thread? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var lastBoostApplied: Boolean? = null
    private var lastPausedApplied: Boolean? = null
    private var inMatchPolls = 0

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        appCtx = app
        startSignalReader(app)
        val t = Thread {
            while (running) {
                try { pollOnce() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "free-booster"
        pollThread = t
        t.start()
        RuntimeLogger.log("FreeBoosterController started (poll ${POLL_MS}ms)", "BOOSTER")
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        stopSignalReader()
        releaseTransportRequest()
        releaseWakeLock()
        applyKeepaliveBoost(false)
        applyDnsPaused(false)
        appCtx = null
        RuntimeLogger.log("FreeBoosterController stopped", "BOOSTER")
    }

    // ---- one poll: edges, readings, decision, actions, readings log ----

    private fun pollOnce() {
        val now = SystemClock.elapsedRealtime()
        val inGame = try { VisionTrust.isGameForeground() } catch (_: Throwable) { false }
        when (FreeBoosterEngine.onPoll(inGame)) {
            FreeBoosterEngine.MatchEdge.STARTED -> onMatchStarted()
            FreeBoosterEngine.MatchEdge.ENDED -> onMatchEnded()
            null -> {}
        }
        if (!inGame) {
            inMatchPolls = 0
            return
        }
        inMatchPolls++
        val readings = gatherReadings()
        val decision = FreeBoosterEngine.decide(true, readings)
        applyKeepaliveBoost(decision.keepaliveBoost)
        ensureWakeLockHeld()
        if (decision.shedOwnWork) {
            if (now - lastShedMs >= SHED_COOLDOWN_MS) {
                lastShedMs = now
                val dropped = try { LogMirror.dropQueuedRoutine() } catch (_: Throwable) { null }
                if (dropped != null) FreeBoosterEngine.noteShed(dropped.count, dropped.bytes)
                applyDnsPaused(true)
                RuntimeLogger.log(
                    "BOOSTER SHED reasons=${decision.reasons} droppedLines=${dropped?.count ?: -1} droppedBytes=${dropped?.bytes ?: -1} (routine log lines only; crash-waiter lines kept)",
                    "BOOSTER"
                )
            }
        } else {
            applyDnsPaused(false)
        }
        if (inMatchPolls % SNAPSHOT_EVERY_POLLS == 1) {
            RuntimeLogger.log(FreeBoosterEngine.snapshot(readings, true), "BOOSTER")
        }
    }

    private fun gatherReadings(): FreeBoosterEngine.Readings {
        return FreeBoosterEngine.Readings(
            rttMs = NetProbeEngine.rtt,
            jitterMs = NetProbeEngine.jitter,
            lossPct = PacketLossProbeEngine.lossPct,
            quality = NetProbeEngine.quality,
            signalLevel = FreeBoosterEngine.signalLevel,
            signalDbm = FreeBoosterEngine.signalDbm,
            transport = NetworkStateEngine.transport,
            congested = CongestionSentinelEngine.congested,
            verdict = ActionWindowEngine.verdict,
            jitterTolMs = CarrierProfileEngine.jitterTolMs.toFloat()
        )
    }

    // ---- match edges ----

    private fun onMatchStarted() {
        FreeBoosterEngine.noteMatchStarted()
        requestTransportPreference()
        acquireWakeLock()
        applyKeepaliveBoost(true)
        RuntimeLogger.log(
            "BOOSTER MATCH START " + FreeBoosterEngine.snapshot(gatherReadings(), true),
            "BOOSTER"
        )
    }

    private fun onMatchEnded() {
        releaseTransportRequest()
        releaseWakeLock()
        applyKeepaliveBoost(false)
        applyDnsPaused(false)
        inMatchPolls = 0
        RuntimeLogger.log(
            "BOOSTER MATCH END " + FreeBoosterEngine.snapshot(gatherReadings(), false),
            "BOOSTER"
        )
    }

    // ---- keepalive boost + DNS pause (applied only on change) ----

    private fun applyKeepaliveBoost(boost: Boolean) {
        if (lastBoostApplied == boost) return
        lastBoostApplied = boost
        try { RadioKeepAliveEngine.setMatchBoost(boost) } catch (_: Throwable) {}
    }

    private fun applyDnsPaused(paused: Boolean) {
        if (lastPausedApplied == paused) return
        lastPausedApplied = paused
        try { DnsWarmupEngine.setPaused(paused) } catch (_: Throwable) {}
    }

    // ---- transport preference request (current transport only) ----

    private fun requestTransportPreference() {
        val app = appCtx ?: return
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply {
                    when (NetworkStateEngine.transport) {
                        "WIFI" -> addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        "CELLULAR" -> addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        // NONE / OTHER: generic request, no transport
                        // preference — asking for a transport we are not
                        // on would try to move the phone.
                    }
                }.build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    RuntimeLogger.log("BOOSTER TRANSPORT available (re-learning link now)", "BOOSTER")
                    try { NetProbeEngine.onLinkChanged() } catch (_: Throwable) {}
                }

                override fun onLost(network: Network) {
                    RuntimeLogger.log("BOOSTER TRANSPORT LOST (re-learning link now)", "BOOSTER")
                    try { NetProbeEngine.onLinkChanged() } catch (_: Throwable) {}
                }
            }
            cm.requestNetwork(req, cb)
            connectivityManager = cm
            networkCallback = cb
            RuntimeLogger.log(
                "BOOSTER transport preference requested for ${NetworkStateEngine.transport} (this app's process only; cannot pin the game transport or stop a tower switch)",
                "BOOSTER"
            )
        } catch (t: Throwable) {
            RuntimeLogger.log("BOOSTER transport request failed: ${t.message}", "BOOSTER")
        }
    }

    private fun releaseTransportRequest() {
        val cm = connectivityManager
        val cb = networkCallback
        connectivityManager = null
        networkCallback = null
        if (cm != null && cb != null) {
            try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
    }

    // ---- screen keep-awake ----

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val app = appCtx ?: return
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "SplendorAssist:BoosterScreen"
            ).also { wakeLock = it }
            if (!lock.isHeld) lock.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            RuntimeLogger.log("BOOSTER wake lock acquire failed: ${t.message}", "BOOSTER")
        }
    }

    private fun ensureWakeLockHeld() {
        // The 10-minute timeout releases the lock by itself; re-acquire
        // on each in-match poll while it is not held.
        val lock = wakeLock ?: return
        try {
            if (!lock.isHeld) lock.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (_: Throwable) {}
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null) {
            try { if (lock.isHeld) lock.release() } catch (_: Throwable) {}
        }
    }

    // ---- real signal reading (permission-gated, never invented) ----

    @Suppress("DEPRECATION")
    private fun registerLegacyListener(tm: TelephonyManager): PhoneStateListener {
        val listener = object : PhoneStateListener() {
            // The compiler's own KT-47902 fix: an override of a deprecated
            // member must itself be marked deprecated (a @Suppress on this
            // declaration does not silence it — tried, still warns). The
            // function is a framework callback nobody calls; the marker
            // records that this whole listener exists only for API 26-30.
            @Deprecated("Pre-31 signal path only; the listener API is deprecated by Android.")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                if (signalStrength != null) updateSignalFrom(signalStrength)
            }
        }
        Handler(Looper.getMainLooper()).post {
            try { tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) } catch (_: Throwable) {}
        }
        return listener
    }

    @Suppress("DEPRECATION")
    private fun unregisterLegacyListener(tm: TelephonyManager, listener: PhoneStateListener) {
        try { tm.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Throwable) {}
    }

    private fun startSignalReader(app: Context) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            if (!signalGrantLogged) {
                signalGrantLogged = true
                RuntimeLogger.log(PHONE_PERMISSION_REQUEST_NOTE, "BOOSTER")
            }
            return
        }
        try {
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val scoped = try {
                tm.createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
            } catch (_: Throwable) { tm }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        updateSignalFrom(signalStrength)
                    }
                }
                scoped.registerTelephonyCallback(app.mainExecutor, cb)
                telephonyCallback = cb
            } else {
                // Pre-31: the listener must be registered from a thread
                // with a Looper, so the registration is posted to the main
                // thread. Deprecated in 31, the only path below it.
                phoneStateListener = registerLegacyListener(scoped)
            }
        } catch (t: Throwable) {
            RuntimeLogger.log("BOOSTER signal reader failed: ${t.message}", "BOOSTER")
        }
    }

    private fun stopSignalReader() {
        val app = appCtx
        if (app != null) {
            try {
                val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val cb = telephonyCallback
                if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { tm.unregisterTelephonyCallback(cb) } catch (_: Throwable) {}
                }
                val listener = phoneStateListener
                if (listener != null) {
                    unregisterLegacyListener(tm, listener)
                }
            } catch (_: Throwable) {}
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    private fun updateSignalFrom(signalStrength: SignalStrength) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val first: CellSignalStrength? = signalStrength.cellSignalStrengths.firstOrNull()
                if (first != null) {
                    val dbm = first.dbm
                    FreeBoosterEngine.updateSignal(
                        first.level,
                        if (dbm == Int.MAX_VALUE) 0 else dbm
                    )
                    return
                }
            }
            // Below 29, or no cell entry: level only, dBm unknown.
            @Suppress("DEPRECATION")
            FreeBoosterEngine.updateSignal(signalStrength.level, 0)
        } catch (_: Throwable) {}
    }
}
