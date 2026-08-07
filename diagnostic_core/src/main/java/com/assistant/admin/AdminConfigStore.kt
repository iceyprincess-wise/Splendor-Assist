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
 * - Defaults are EXACTLY the previous hard-coded values; with no stored
 *   overrides the runtime behaves identically to the pre-migration build.
 * - Safe pre-initialize: getters fall back to the compiled default until
 *   initialize(context) runs, so engine start order cannot break anything.
 *
 * Every tunable is tagged with its adapter + engine so the admin panel
 * builds its Adapter -> Engine -> Settings navigation automatically.
 * Exposing a new engine = adding its Tunables here (plus guides in
 * AdminTuningGuide). No UI work needed — buttons build themselves.
 */
object AdminConfigStore {

    const val ADAPTER_NET = "Net Adapter"
    const val ADAPTER_STUTTER = "Stutter Adapter"
    const val ADAPTER_LAG = "Lag Adapter"

    /** Fixed panel order. Adapters with no tunables yet still get a button. */
    val ADAPTERS: List<String> = listOf(ADAPTER_NET, ADAPTER_STUTTER, ADAPTER_LAG)

    data class Tunable(
        val key: String,
        val label: String,
        val def: Float,
        val adapter: String,
        val engine: String
    )

    // ---- The 23 migrated constants (defaults = previous hard-coded values) ----
    val TUNABLES: List<Tunable> = listOf(
        // NetProbeEngine
        Tunable("net.probe.fast_ms",           "NetProbe dirty-link cadence (ms)",      2000f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.calm_ms",           "NetProbe clean-link cadence (ms)",      5000f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.timeout_ms",        "NetProbe TCP connect timeout (ms)",     1200f,  ADAPTER_NET, "NetProbeEngine"),
        Tunable("net.probe.alpha",             "NetProbe EWMA alpha",                   0.35f,  ADAPTER_NET, "NetProbeEngine"),
        // PacketLossProbeEngine
        Tunable("net.loss.round_ms",           "Loss probe round interval (ms)",        4000f,  ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.per_round",          "Loss probe queries per round",          4f,     ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.reply_timeout_ms",   "Loss probe reply timeout (ms)",         700f,   ADAPTER_NET, "PacketLossProbeEngine"),
        Tunable("net.loss.alpha",              "Loss EWMA alpha",                       0.3f,   ADAPTER_NET, "PacketLossProbeEngine"),
        // DnsWarmupEngine
        Tunable("net.dns.rewarm_ms",           "DNS re-warm interval (ms)",             90000f, ADAPTER_NET, "DnsWarmupEngine"),
        // CongestionSentinelEngine
        Tunable("net.sentinel.poll_ms",        "Sentinel poll cadence (ms)",            2000f,  ADAPTER_NET, "CongestionSentinelEngine"),
        Tunable("net.sentinel.rise_factor",    "Sentinel jitter rise factor",           1.5f,   ADAPTER_NET, "CongestionSentinelEngine"),
        Tunable("net.sentinel.rise_fraction",  "Sentinel rise fraction of tolerance",   0.6f,   ADAPTER_NET, "CongestionSentinelEngine"),
        // ActionWindowEngine
        Tunable("net.window.poll_ms",          "Action window refresh (ms)",            2000f,  ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.hold_loss_pct",    "HOLD when loss above (%)",              10f,    ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.go_loss_pct",      "GO requires loss below (%)",            2f,     ADAPTER_NET, "ActionWindowEngine"),
        Tunable("net.window.hold_jitter_mult", "HOLD when jitter > tolerance x",        2f,     ADAPTER_NET, "ActionWindowEngine"),
        // SpikeBurstEngine
        Tunable("net.spike.recovery_window_ms","Spike recovery watch window (ms)",      60000f, ADAPTER_NET, "SpikeBurstEngine"),
        Tunable("net.spike.clean_samples",     "Clean samples to declare recovery",     2f,     ADAPTER_NET, "SpikeBurstEngine"),
        // RadioKeepAliveEngine
        Tunable("net.keepalive.floor_s",       "Keep-alive dirty-link floor (s)",       4f,     ADAPTER_NET, "RadioKeepAliveEngine"),
        // FramePacingEngine
        Tunable("lag.frame.alpha",             "Frame pacing EWMA alpha",               0.2f,   ADAPTER_LAG, "FramePacingEngine"),
        Tunable("lag.frame.report_ms",         "Frame pacing report window (ms)",       20000f, ADAPTER_LAG, "FramePacingEngine"),
        Tunable("lag.frame.stall_ms",          "Hard stall threshold (ms)",             100f,   ADAPTER_LAG, "FramePacingEngine"),
        // LoadShedGovernor
        Tunable("lag.shed.min_hold_ms",        "Load shed min hold (ms)",               8000f,  ADAPTER_LAG, "LoadShedGovernor")
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
