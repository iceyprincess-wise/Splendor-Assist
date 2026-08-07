package com.assistant.admin

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Live readings published by the net AND lag engines so the admin panel's
 * Detector can recommend values from what THIS device is measuring RIGHT
 * NOW - no guessing.
 *
 * The engines run in separate processes from the admin screen, so
 * in-memory fields alone are invisible to the panel. Every publish
 * therefore also writes a tiny JSON snapshot into the app's private
 * filesDir (shared by every process of this app); readers transparently
 * reload that snapshot whenever their own in-memory copy is stale.
 * No permissions, no extra threads, no polling loops.
 */
object AdminLiveStats {

    // ---- net side ----
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

    // ---- lag side ----
    @Volatile var frameGapMs = 0f; private set
    @Volatile var frameJitterMs = 0f; private set
    @Volatile var stabilityPct = -1f; private set     // -1 = not measured yet
    @Volatile var stallsPerMin = -1f; private set
    @Volatile var mtStallMs = -1f; private set
    @Volatile var mtSpikesPerMin = -1f; private set
    @Volatile var lagVerdict = "MEASURING"; private set
    @Volatile var thermal = "?"; private set
    @Volatile var panelHz = 0f; private set
    @Volatile var shedLevel = "NONE"; private set
    @Volatile var lagUpdatedMs = 0L; private set

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

    fun publishLag(gap: Float, fJitter: Float, stability: Float, stalls: Float,
                   mtStall: Float, spikes: Float, verdict: String, therm: String,
                   hz: Float, shed: String) {
        frameGapMs = gap; frameJitterMs = fJitter; stabilityPct = stability
        stallsPerMin = stalls; mtStallMs = mtStall; mtSpikesPerMin = spikes
        lagVerdict = verdict; thermal = therm; panelHz = hz; shedLevel = shed
        lagUpdatedMs = System.currentTimeMillis()
        save()
    }

    /** True when net probe numbers are fresh enough to trust (within 30s). */
    fun fresh(): Boolean {
        if (!memFresh()) load()
        return memFresh()
    }

    /** True when lag numbers are fresh enough to trust (within 30s). */
    fun lagFresh(): Boolean {
        if (!memLagFresh()) load()
        return memLagFresh()
    }

    private fun memFresh(): Boolean =
        probeUpdatedMs > 0 && System.currentTimeMillis() - probeUpdatedMs < 30_000L

    private fun memLagFresh(): Boolean =
        lagUpdatedMs > 0 && System.currentTimeMillis() - lagUpdatedMs < 30_000L

    // ---- cross-process snapshot ----

    private fun file(): File? = appCtx?.let { File(it.filesDir, "admin_live_stats.json") }

    @Synchronized
    private fun save() {
        try {
            val f = file() ?: return
            // merge-on-write: keep the other side's newer numbers when two
            // processes (net kernel / lag process) share the snapshot file
            load()
            val o = JSONObject()
            o.put("rtt", rttMs.toDouble()); o.put("jitter", jitterMs.toDouble())
            o.put("loss", lossPct.toDouble()); o.put("quality", quality)
            o.put("transport", transport); o.put("carrier", carrier)
            o.put("baseRtt", baselineRttMs); o.put("jitTol", jitterTolMs)
            o.put("probeAt", probeUpdatedMs); o.put("lossAt", lossUpdatedMs)
            o.put("fGap", frameGapMs.toDouble()); o.put("fJitter", frameJitterMs.toDouble())
            o.put("stability", stabilityPct.toDouble()); o.put("stalls", stallsPerMin.toDouble())
            o.put("mtStall", mtStallMs.toDouble()); o.put("mtSpikes", mtSpikesPerMin.toDouble())
            o.put("lagVerdict", lagVerdict); o.put("thermal", thermal)
            o.put("panelHz", panelHz.toDouble()); o.put("shed", shedLevel)
            o.put("lagAt", lagUpdatedMs)
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
            val gAt = o.optLong("lagAt")
            if (gAt > lagUpdatedMs) {
                frameGapMs = o.optDouble("fGap", 0.0).toFloat()
                frameJitterMs = o.optDouble("fJitter", 0.0).toFloat()
                stabilityPct = o.optDouble("stability", -1.0).toFloat()
                stallsPerMin = o.optDouble("stalls", -1.0).toFloat()
                mtStallMs = o.optDouble("mtStall", -1.0).toFloat()
                mtSpikesPerMin = o.optDouble("mtSpikes", -1.0).toFloat()
                lagVerdict = o.optString("lagVerdict", "MEASURING")
                thermal = o.optString("thermal", "?")
                panelHz = o.optDouble("panelHz", 0.0).toFloat()
                shedLevel = o.optString("shed", "NONE")
                lagUpdatedMs = gAt
            }
        } catch (_: Throwable) { }
    }
}
