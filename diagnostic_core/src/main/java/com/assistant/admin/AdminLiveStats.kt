package com.assistant.admin

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Live network readings published by the net engines so the admin panel's
 * Detector can recommend values from what THIS device is measuring RIGHT
 * NOW - no guessing.
 *
 * The net engines run in a separate process (:kernel) from the admin
 * screen, so in-memory fields alone are invisible to the panel. Every
 * publish therefore also writes a tiny JSON snapshot into the app's
 * private filesDir (shared by every process of this app); readers
 * transparently reload that snapshot whenever their own in-memory copy
 * is stale. No permissions, no extra threads, no polling loops.
 */
object AdminLiveStats {

    @Volatile var rttMs = 0f; private set
    @Volatile var jitterMs = 0f; private set
    @Volatile var lossPct = -1f; private set          // -1 = not measured yet
    @Volatile var quality = "MEASURING"; private set
    @Volatile var transport = "UNKNOWN"; private set
    @Volatile var carrier = "UNKNOWN"; private set
    @Volatile var baselineRttMs = 0; private set
    @Volatile var jitterTolMs = 0; private set
    @Volatile var probeUpdatedMs = 0L; private set
    @Volatile var lossUpdatedMs = 0L; private set

    @Volatile private var appCtx: Context? = null

    /** Idempotent; called from AdminConfigStore.initialize in every process. */
    fun initialize(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    fun publishProbe(rtt: Float, jitter: Float, q: String, carrierName: String,
                     baseRtt: Int, jitTol: Int, trans: String) {
        rttMs = rtt; jitterMs = jitter; quality = q; carrier = carrierName
        baselineRttMs = baseRtt; jitterTolMs = jitTol; transport = trans
        probeUpdatedMs = System.currentTimeMillis()
        save()
    }

    fun publishLoss(pct: Float) {
        lossPct = pct
        lossUpdatedMs = System.currentTimeMillis()
        save()
    }

    /**
     * True when probe numbers are fresh enough to trust (updated within 30s).
     * If this process's copy is stale, the engine process's snapshot is
     * loaded first - so the panel sees live numbers even though the engines
     * run in :kernel.
     */
    fun fresh(): Boolean {
        if (!memFresh()) load()
        return memFresh()
    }

    private fun memFresh(): Boolean =
        probeUpdatedMs > 0 && System.currentTimeMillis() - probeUpdatedMs < 30_000L

    // ---- cross-process snapshot ----

    private fun file(): File? = appCtx?.let { File(it.filesDir, "admin_live_stats.json") }

    private fun save() {
        try {
            val f = file() ?: return
            val o = JSONObject()
            o.put("rtt", rttMs.toDouble()); o.put("jitter", jitterMs.toDouble())
            o.put("loss", lossPct.toDouble()); o.put("quality", quality)
            o.put("transport", transport); o.put("carrier", carrier)
            o.put("baseRtt", baselineRttMs); o.put("jitTol", jitterTolMs)
            o.put("probeAt", probeUpdatedMs); o.put("lossAt", lossUpdatedMs)
            f.writeText(o.toString())
        } catch (_: Throwable) { }
    }

    private fun load() {
        try {
            val f = file() ?: return
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val pAt = o.optLong("probeAt")
            if (pAt > probeUpdatedMs) {
                rttMs = o.optDouble("rtt", 0.0).toFloat()
                jitterMs = o.optDouble("jitter", 0.0).toFloat()
                quality = o.optString("quality", "MEASURING")
                transport = o.optString("transport", "UNKNOWN")
                carrier = o.optString("carrier", "UNKNOWN")
                baselineRttMs = o.optInt("baseRtt", 0)
                jitterTolMs = o.optInt("jitTol", 0)
                probeUpdatedMs = pAt
            }
            val lAt = o.optLong("lossAt")
            if (lAt > lossUpdatedMs) {
                lossPct = o.optDouble("loss", -1.0).toFloat()
                lossUpdatedMs = lAt
            }
        } catch (_: Throwable) { }
    }
}
