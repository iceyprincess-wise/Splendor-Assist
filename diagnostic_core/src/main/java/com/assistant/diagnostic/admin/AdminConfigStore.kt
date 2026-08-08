package com.assistant.diagnostic.admin

// TASK A - ADMIN SETTINGS (new isolated file per directive)
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.assistant.diagnostic.RuntimeLogger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central owner of the 23 runtime tuning constants previously hard-coded
 * across the net/lag engine stacks. Defaults are EXACTLY the pre-migration
 * values: with no stored overrides, behaviour is identical to the old build.
 *
 * Storage:
 *   - SharedPreferences "admin_config" (authoritative)
 *   - JSON mirror at Downloads/SplendorAssist/admin_config.json
 *     (falls back to app filesDir when Downloads is unavailable)
 *
 * Reads are lock-free (ConcurrentHashMap of primed values) so engine hot
 * loops pay no prefs cost per tick.
 */
object AdminConfigStore {

    private const val PREFS_NAME = "admin_config"
    private const val KEY_PIN = "admin_pin"
    private const val DEFAULT_PIN = "2468"

    data class Spec(val key: String, val label: String, val def: Float)

    // ---- The 23 migrated constants (key -> previous hard-coded value) ----
    val SPECS: List<Spec> = listOf(
        Spec("net_probe_fast_ms",        "NetProbe fast cadence ms (dirty link)",  2000f),
        Spec("net_probe_calm_ms",        "NetProbe calm cadence ms (clean link)",  5000f),
        Spec("net_probe_timeout_ms",     "NetProbe TCP connect timeout ms",        1200f),
        Spec("net_probe_alpha",          "NetProbe EWMA alpha",                    0.35f),
        Spec("loss_round_ms",            "PacketLoss round interval ms",           4000f),
        Spec("loss_per_round",           "PacketLoss queries per round",           4f),
        Spec("loss_reply_timeout_ms",    "PacketLoss UDP reply timeout ms",        700f),
        Spec("loss_alpha",               "PacketLoss EWMA alpha",                  0.3f),
        Spec("dns_rewarm_ms",            "DNS re-warm interval ms",                90000f),
        Spec("sentinel_cadence_ms",      "Congestion sentinel poll ms",            2000f),
        Spec("sentinel_rise_factor",     "Congestion jitter rise factor",          1.5f),
        Spec("sentinel_rise_fraction",   "Congestion rise fraction of tolerance",  0.6f),
        Spec("window_cadence_ms",        "Action window refresh ms",               2000f),
        Spec("window_hold_loss_pct",     "Action window HOLD loss %",              10f),
        Spec("window_go_loss_pct",       "Action window GO loss %",                2f),
        Spec("window_jitter_hold_mult",  "Action window HOLD jitter multiplier",   2f),
        Spec("spike_recovery_window_ms", "Spike recovery watch window ms",         60000f),
        Spec("spike_clean_needed",       "Spike clean samples to clear",           2f),
        Spec("radio_keepalive_floor_s",  "Radio keep-alive floor seconds",         4f),
        Spec("frame_alpha",              "Frame pacing EWMA alpha",                0.2f),
        Spec("frame_report_every_ms",    "Frame pacing report window ms",          20000f),
        Spec("frame_stall_ms",           "Hard stall threshold ms",                100f),
        Spec("shed_min_hold_ms",         "Load shed min hold ms",                  8000f)
    )

    @Volatile private var prefs: SharedPreferences? = null
    private val values = ConcurrentHashMap<String, Float>()
    @Volatile private var pin = DEFAULT_PIN

    init { for (s in SPECS) values[s.key] = s.def }

    /** Idempotent; safe to call from any engine start path. */
    fun initialize(ctx: Context) {
        if (prefs != null) return
        try {
            val p = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            for (s in SPECS) values[s.key] = p.getFloat(s.key, s.def)
            pin = p.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
            RuntimeLogger.log("AdminConfigStore loaded " + SPECS.size + " keys", "ADMIN")
            mirrorToFile(ctx.applicationContext)
        } catch (_: Throwable) { }
    }

    fun get(key: String): Float = values[key] ?: 0f
    fun getMs(key: String): Long = (values[key] ?: 0f).toLong()
    fun getInt(key: String): Int = (values[key] ?: 0f).toInt()

    fun set(ctx: Context, key: String, value: Float) {
        values[key] = value
        try { prefs?.edit()?.putFloat(key, value)?.apply() } catch (_: Throwable) { }
        mirrorToFile(ctx.applicationContext)
    }

    fun checkPin(candidate: String): Boolean = candidate == pin

    fun setPin(ctx: Context, newPin: String) {
        if (newPin.isBlank()) return
        pin = newPin
        try { prefs?.edit()?.putString(KEY_PIN, newPin)?.apply() } catch (_: Throwable) { }
    }

    fun resetAll(ctx: Context) { for (s in SPECS) set(ctx, s.key, s.def) }

    private fun mirrorToFile(ctx: Context) {
        try {
            val json = JSONObject()
            for (s in SPECS) json.put(s.key, values[s.key])
            val dl = try {
                File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "SplendorAssist").apply { mkdirs() }
            } catch (_: Throwable) { null }
            val target = if (dl != null && dl.exists()) dl else ctx.filesDir
            File(target, "admin_config.json").writeText(json.toString(2))
        } catch (_: Throwable) { }
    }
}
