package com.assistant.admin

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.assistant.diagnostic.RuntimeLogger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for the admin-tunable runtime constants.
 *
 * - SharedPreferences ("admin_config") is the authoritative store.
 * - A JSON mirror is written to Downloads/SplendorAssist/admin_config.json
 *   (falls back to filesDir) after every change, for offline inspection.
 * - Reads are lock-free: values are primed into a ConcurrentHashMap so hot
 *   engine loops never touch prefs on their tick path.
 * - Defaults are EXACTLY the engine's factory values; with no stored
 *   overrides the runtime behaves identically.
 * - Safe pre-initialize: getters fall back to the compiled default until
 *   initialize(context) runs, so engine start order cannot break anything.
 *
 * Every tunable is tagged with its adapter + engine, and every engine
 * belongs to a CATEGORY (speed / shield / brain) so the panel groups them
 * in plain language. Exposing a new engine = adding its Tunables here plus
 * guides in AdminTuningGuide.
 */
object AdminConfigStore {

    const val ADAPTER_NET = "Net Adapter"
    const val ADAPTER_STUTTER = "Stutter Adapter"
    const val ADAPTER_LAG = "Lag Adapter"

    /** Fixed panel order. Adapters with no tunables yet still get a button. */
    val ADAPTERS: List<String> = listOf(ADAPTER_NET, ADAPTER_STUTTER, ADAPTER_LAG)

    // ---- plain-language engine categories ----
    const val CAT_SPEED = "NETWORK SPEED - tweak these for the fastest feel"
    const val CAT_SHIELD = "NETWORK SHIELD - stop lag and loss before you feel them"
    const val CAT_BRAIN = "NETWORK BRAIN - the judge that decides GO or HOLD"
    const val CAT_GENERAL = "GENERAL"

    fun categoryFor(engine: String): String = when (engine) {
        "NetProbeEngine", "NetworkStateEngine", "DnsWarmupEngine", "RadioKeepAliveEngine" -> CAT_SPEED
        "PacketLossProbeEngine", "CongestionSentinelEngine", "SpikeBurstEngine" -> CAT_SHIELD
        "ActionWindowEngine", "CarrierProfileEngine" -> CAT_BRAIN
        else -> CAT_GENERAL
    }

    /** One-line plain-language job description per engine. */
    fun engineBlurb(engine: String): String = when (engine) {
        "NetProbeEngine" -> "The heartbeat checker. Pings the internet non-stop and tells every other engine how fast and steady your connection is right now."
        "NetworkStateEngine" -> "The switch watcher. Notices the very second you move between WiFi and mobile data and makes the whole stack re-learn the new connection instantly."
        "DnsWarmupEngine" -> "The address book keeper. Keeps server addresses pre-looked-up so connections never start cold."
        "RadioKeepAliveEngine" -> "The radio waker. Stops your phone's modem from dozing off, so your next action never pays the wake-up delay."
        "PacketLossProbeEngine" -> "The lost-packet counter. Measures how many of your packets actually die on the way. A lost packet is a lost pass."
        "CongestionSentinelEngine" -> "The traffic watchman. Feels congestion building BEFORE you feel the lag, and raises the alarm."
        "SpikeBurstEngine" -> "The spike mapper. When trouble hits it measures how bad the spike is, then announces the all-clear seconds earlier than anyone else."
        "ActionWindowEngine" -> "The traffic light. Combines everything into one verdict: GO (play full speed), CAUTION (play safe), HOLD (do not commit)."
        "CarrierProfileEngine" -> "The rulebook. The baseline your connection is judged against (MTN, AIRTEL, WiFi...). Leave on auto, or set your own baseline here."
        else -> ""
    }

    data class Tunable(
        val key: String,
        val label: String,
        val def: Float,
        val adapter: String,
        val engine: String
    )

    // ---- All admin-tunable constants (defaults = engine factory values) ----
    val TUNABLES: List<Tunable> = listOf(
        // NetProbeEngine
        Tunable("net.probe.fast_ms",            "How often to ping when connection is BAD (ms)",          2000f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.calm_ms",            "How often to ping when connection is GOOD (ms)",         5000f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.timeout_ms",         "How long to wait before a ping counts as failed (ms)",   1200f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.alpha",              "Memory: how fast the average follows new pings (0-1)",   0.35f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.samples",            "Pings fired per check (middle one is used)",             3f,     ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.gap_ms",             "Gap between the pings in one check (ms)",                60f,    ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.degraded_mult",      "Ping above baseline x this = connection DEGRADED",       2f,     ADAPTER_NET, "NetProbeEngine"),
        // NetworkStateEngine
        Tunable("net.state.poll_ms",            "Backup check of WiFi/data switch (ms)",                  10000f, ADAPTER_NET, "NetworkStateEngine"),
        // DnsWarmupEngine
        Tunable("net.dns.rewarm_ms",            "How often to refresh server addresses (ms)",             90000f, ADAPTER_NET, "DnsWarmupEngine"),
        // RadioKeepAliveEngine
        Tunable("net.keepalive.floor_s",        "Fastest radio-wake rhythm on a bad link (s)",            4f,     ADAPTER_NET, "RadioKeepAliveEngine"),
        // PacketLossProbeEngine
        Tunable("net.loss.round_ms",            "How often to run a lost-packet check (ms)",              4000f,  ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.per_round",           "Packets sent per check",                                  4f,     ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.reply_timeout_ms",    "How long to wait for each reply (ms)",                   700f,   ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.alpha",               "Memory: how fast the loss average follows new checks (0-1)", 0.3f, ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.gap_ms",              "Gap between packets in one check (ms)",                  80f,    ADAPTER_NET, "PacketLossProbeEngine"),
        // CongestionSentinelEngine
        Tunable("net.sentinel.poll_ms",         "How often to check for congestion building (ms)",        2000f,  ADAPTER_NET, "CongestionSentinelEngine"),
        Tunable("net.sentinel.rise_factor",     "Alarm when wobble jumps x this over last reading",       1.5f,   ADAPTER_NET, "CongestionSentinelEngine"),
        Tunable("net.sentinel.rise_fraction",   "...and wobble is above this share of allowance (0-1)",   0.6f,   ADAPTER_NET, "CongestionSentinelEngine"),
        // SpikeBurstEngine
        Tunable("net.spike.recovery_window_ms", "After a spike: how long to watch for recovery (ms)",     60000f, ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.clean_samples",      "Clean pings in a row = all-clear",                       2f,     ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.burst_samples",      "Pings fired to map a spike",                             5f,     ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.burst_gap_ms",       "Gap between spike-mapping pings (ms)",                   200f,   ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.clean_mult",         "Ping under baseline x this counts as clean",             1.5f,   ADAPTER_NET, "SpikeBurstEngine"),
        // ActionWindowEngine
        Tunable("net.window.poll_ms",           "How often to refresh the GO/HOLD verdict (ms)",          2000f,  ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.hold_loss_pct",     "HOLD when lost packets above (%)",                       10f,    ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.go_loss_pct",       "GO needs lost packets below (%)",                        2f,     ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.hold_jitter_mult",  "HOLD when wobble above allowance x this",                2f,     ADAPTER_NET, "ActionWindowEngine"),
        // CarrierProfileEngine (0 = automatic, follow detected carrier)
        Tunable("net.profile.rtt_ms",           "Your ping pass-line (ms, 0 = auto by carrier)",          0f,     ADAPTER_NET, "CarrierProfileEngine"),
        Tunable("net.profile.jitter_tol_ms",    "Your wobble allowance (ms, 0 = auto by carrier)",        0f,     ADAPTER_NET, "CarrierProfileEngine"),
        Tunable("net.profile.keepalive_s",      "Radio ping rhythm (s, 0 = auto by carrier)",             0f,     ADAPTER_NET, "CarrierProfileEngine"),
        // FramePacingEngine
        Tunable("lag.frame.alpha",              "Memory: how fast the frame average follows new frames (0-1)", 0.2f, ADAPTER_LAG, "FramePacingEngine"),
        Tunable("lag.frame.report_ms",          "Frame smoothness report window (ms)",                    20000f, ADAPTER_LAG, "FramePacingEngine"),
        Tunable("lag.frame.stall_ms",           "A frame slower than this = a freeze (ms)",               100f,   ADAPTER_LAG, "FramePacingEngine"),
        // LoadShedGovernor
        Tunable("lag.shed.min_hold_ms",         "Once helping starts, keep helping at least (ms)",        8000f,  ADAPTER_LAG, "LoadShedGovernor")
    )

    // ---- grouping helpers for the panel ----
    fun enginesFor(adapter: String): List<String> =
        TUNABLES.filter { it.adapter == adapter }.map { it.engine }.distinct()

    fun tunablesFor(adapter: String, engine: String): List<Tunable> =
        TUNABLES.filter { it.adapter == adapter && it.engine == engine }

    private const val PREFS = "admin_config"
    private const val KEY_PIN = "admin.pin"
    private const val DEFAULT_PIN = "2468"

    private val cache = ConcurrentHashMap<String, Float>()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appCtx: Context? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        appCtx = ctx
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for (t in TUNABLES) cache[t.key] = p.getFloat(t.key, t.def)
        RuntimeLogger.log("AdminConfigStore ready (" + TUNABLES.size + " tunables)", "ADMIN")
    }

    // ---- lock-free reads (pre-init falls back to compiled defaults) ----
    fun get(key: String, def: Float): Float = cache[key] ?: def
    fun getInt(key: String, def: Int): Int = (cache[key] ?: def.toFloat()).toInt()
    fun getLong(key: String, def: Long): Long = (cache[key] ?: def.toFloat()).toLong()

    fun set(key: String, value: Float) {
        cache[key] = value
        prefs?.edit()?.putFloat(key, value)?.apply()
        mirror()
    }

    fun resetAll() {
        val e = prefs?.edit()
        for (t in TUNABLES) { cache[t.key] = t.def; e?.putFloat(t.key, t.def) }
        e?.apply()
        mirror()
        RuntimeLogger.log("AdminConfigStore reset to defaults", "ADMIN")
    }

    /** Reset only one engine's tunables to compiled defaults. */
    fun resetEngine(adapter: String, engine: String) {
        val e = prefs?.edit()
        for (t in tunablesFor(adapter, engine)) { cache[t.key] = t.def; e?.putFloat(t.key, t.def) }
        e?.apply()
        mirror()
        RuntimeLogger.log("AdminConfigStore reset engine: " + engine, "ADMIN")
    }

    // ---- PIN gate ----
    fun checkPin(pin: String): Boolean =
        pin == (prefs?.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN)

    fun setPin(pin: String) { prefs?.edit()?.putString(KEY_PIN, pin)?.apply() }

    // ---- file mirror ----
    private fun mirror() {
        try {
            val o = JSONObject()
            for (t in TUNABLES) o.put(t.key, (cache[t.key] ?: t.def).toDouble())
            val target = mirrorFile() ?: return
            target.parentFile?.mkdirs()
            target.writeText(o.toString(2))
        } catch (t: Throwable) {
            RuntimeLogger.log("config mirror failed: " + t.message, "ADMIN")
        }
    }

    private fun mirrorFile(): File? = try {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dl != null && (dl.exists() || dl.mkdirs()))
            File(File(dl, "SplendorAssist"), "admin_config.json")
        else appCtx?.filesDir?.let { File(it, "admin_config.json") }
    } catch (_: Throwable) {
        appCtx?.filesDir?.let { File(it, "admin_config.json") }
    }
}
