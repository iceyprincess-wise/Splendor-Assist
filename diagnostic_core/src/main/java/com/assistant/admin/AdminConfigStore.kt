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
 * - Defaults are EXACTLY the engines' compiled values; with no stored
 *   overrides the runtime behaves identically.
 * - Safe pre-initialize: getters fall back to the compiled default until
 *   initialize(context) runs, so engine start order cannot break anything.
 *
 * Every tunable is tagged with its adapter + engine, and every engine with
 * a plain-language category, so the admin panel builds its
 * Adapter -> Category -> Engine -> Settings navigation automatically.
 */
object AdminConfigStore {

    const val ADAPTER_NET = "Net Adapter"
    const val ADAPTER_STUTTER = "Stutter Adapter"
    const val ADAPTER_LAG = "Lag Adapter"

    /** Fixed panel order. Adapters with no tunables yet still get a button. */
    val ADAPTERS: List<String> = listOf(ADAPTER_NET, ADAPTER_STUTTER, ADAPTER_LAG)

    // ---- plain-language engine categories (panel groups engines under these) ----
    const val CAT_SPEED = "Network Speed - tweak these for the fastest possible response"
    const val CAT_GUARD = "Network Guard - spots trouble before you feel it"
    const val CAT_DECISION = "Play Decision - the final GO / HOLD traffic light"
    const val CAT_BASELINE = "Your Network Baseline - what counts as normal for YOUR line"
    const val CAT_SMOOTH = "Smoothness"

    private val ENGINE_CATEGORY: Map<String, String> = mapOf(
        // Net Adapter
        "NetProbeEngine" to CAT_SPEED,
        "RadioKeepAliveEngine" to CAT_SPEED,
        "DnsWarmupEngine" to CAT_SPEED,
        "PacketLossProbeEngine" to CAT_GUARD,
        "CongestionSentinelEngine" to CAT_GUARD,
        "SpikeBurstEngine" to CAT_GUARD,
        "NetworkStateEngine" to CAT_GUARD,
        "ActionWindowEngine" to CAT_DECISION,
        "CarrierProfileEngine" to CAT_BASELINE,
        // Lag Adapter
        "FramePacingEngine" to CAT_SMOOTH,
        "LoadShedGovernor" to CAT_SMOOTH
    )

    /** Category display order inside an adapter. */
    private val CATEGORY_ORDER: List<String> =
        listOf(CAT_SPEED, CAT_GUARD, CAT_DECISION, CAT_BASELINE, CAT_SMOOTH)

    fun categoryOf(engine: String): String = ENGINE_CATEGORY[engine] ?: "Other"

    data class Tunable(
        val key: String,
        val label: String,
        val def: Float,
        val adapter: String,
        val engine: String
    )

    // ---- All migrated constants (defaults = the engines' compiled values) ----
    val TUNABLES: List<Tunable> = listOf(
        // NetProbeEngine
        Tunable("net.probe.fast_ms",           "Check speed when connection is BAD (ms)",   2000f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.calm_ms",           "Check speed when connection is GOOD (ms)",  5000f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.timeout_ms",        "How long one ping waits (ms)",              1200f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.alpha",             "Memory dial: newest ping weight (0-1)",     0.35f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.samples",           "Pings per health check",                    3f,     ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.gap_ms",            "Pause between those pings (ms)",            60f,    ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.degraded_mult",     "Where OK becomes BAD (x baseline)",         2f,     ADAPTER_NET, "NetProbeEngine"),
        // NetworkStateEngine
        Tunable("net.state.poll_ms",           "Backup network-switch sweep (ms)",          10000f, ADAPTER_NET, "NetworkStateEngine"),
        // PacketLossProbeEngine
        Tunable("net.loss.round_ms",           "Lost-packet check rhythm (ms)",             4000f,  ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.per_round",          "Packets per check",                         4f,     ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.reply_timeout_ms",   "How long each packet waits (ms)",           700f,   ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.alpha",              "Memory dial: newest check weight (0-1)",    0.3f,   ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.gap_ms",             "Pause between packets (ms)",                80f,    ADAPTER_NET, "PacketLossProbeEngine"),
        // DnsWarmupEngine
        Tunable("net.dns.rewarm_ms",           "Server-address refresh rhythm (ms)",        90000f, ADAPTER_NET, "DnsWarmupEngine"),
        // CongestionSentinelEngine
        Tunable("net.sentinel.poll_ms",        "Watchman check rhythm (ms)",                2000f,  ADAPTER_NET, "CongestionSentinelEngine"),
        Tunable("net.sentinel.rise_factor",    "Alarm: wobble jump size (x last)",          1.5f,   ADAPTER_NET, "CongestionSentinelEngine"),
        Tunable("net.sentinel.rise_fraction",  "Alarm: second gate (share of allowance)",   0.6f,   ADAPTER_NET, "CongestionSentinelEngine"),
        // SpikeBurstEngine
        Tunable("net.spike.recovery_window_ms","Watch time after a spike (ms)",             60000f, ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.clean_samples",     "Clean pings needed for all-clear",          2f,     ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.burst_samples",     "Pings fired to map a spike",                5f,     ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.burst_gap_ms",      "Pause between mapping pings (ms)",          200f,   ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.clean_mult",        "What counts as clean (x baseline)",         1.5f,   ADAPTER_NET, "SpikeBurstEngine"),
        // RadioKeepAliveEngine
        Tunable("net.keepalive.floor_s",       "Fastest keep-modem-awake rhythm (s)",       4f,     ADAPTER_NET, "RadioKeepAliveEngine"),
        // ActionWindowEngine
        Tunable("net.window.poll_ms",          "Traffic-light refresh rhythm (ms)",         2000f,  ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.hold_loss_pct",    "HOLD when lost packets above (%)",          10f,    ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.go_loss_pct",      "GO needs lost packets below (%)",           2f,     ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.hold_jitter_mult", "HOLD when wobble above (x allowance)",      2f,     ADAPTER_NET, "ActionWindowEngine"),
        // CarrierProfileEngine (0 = automatic by carrier)
        Tunable("net.profile.rtt_ms",          "Ping pass-line override (0 = auto)",        0f,     ADAPTER_NET, "CarrierProfileEngine"),
        Tunable("net.profile.jitter_tol_ms",   "Wobble allowance override (0 = auto)",      0f,     ADAPTER_NET, "CarrierProfileEngine"),
        Tunable("net.profile.keepalive_s",     "Keep-awake rhythm override (0 = auto)",     0f,     ADAPTER_NET, "CarrierProfileEngine"),
        // FramePacingEngine
        Tunable("lag.frame.alpha",             "Memory dial: newest frame weight (0-1)",    0.2f,   ADAPTER_LAG, "FramePacingEngine"),
        Tunable("lag.frame.report_ms",         "Smoothness report rhythm (ms)",             20000f, ADAPTER_LAG, "FramePacingEngine"),
        Tunable("lag.frame.stall_ms",          "A frame slower than this is a freeze (ms)", 100f,   ADAPTER_LAG, "FramePacingEngine"),
        // LoadShedGovernor
        Tunable("lag.shed.min_hold_ms",        "Minimum helping time once started (ms)",    8000f,  ADAPTER_LAG, "LoadShedGovernor")
    )

    // ---- grouping helpers for the panel ----
    fun enginesFor(adapter: String): List<String> =
        TUNABLES.filter { it.adapter == adapter }.map { it.engine }.distinct()

    /** Engines of an adapter grouped by category, in fixed category order. */
    fun categoriesFor(adapter: String): List<Pair<String, List<String>>> {
        val engines = enginesFor(adapter)
        return CATEGORY_ORDER.mapNotNull { cat ->
            val inCat = engines.filter { categoryOf(it) == cat }
            if (inCat.isEmpty()) null else cat to inCat
        }
    }

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
