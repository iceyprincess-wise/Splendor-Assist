package com.assistant.admin

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The worst-moment archive.
 *
 * Every time the engines publish live numbers, that moment is scored. When
 * a moment scores WORSE than anything archived before for that adapter,
 * the Detector's picks - computed from that exact moment's numbers - are
 * archived, together with when it happened and which engine's readings
 * drove it. The archive only ever updates when a moment worse than the
 * last archived one is measured, so what sits here is always the setup
 * tuned for the hardest conditions this device has actually faced.
 *
 * For lag and stutter moments the record also notes whether YOUR network
 * was clean at that instant: when it was, the cause was the opponent's
 * connection or the game server - not your device and not your line.
 * (No app on your phone can repair the opponent's network; what this
 * archive does is prove which side failed and keep the strongest setup
 * for surviving those moments.)
 *
 * Stored in filesDir (shared by every process of this app), same
 * cross-process pattern as AdminLiveStats. Scoring runs before any file
 * I/O, so quiet moments cost nothing.
 */
object AdminWorstMoments {

    // A moment must score at least this before it is worth archiving -
    // keeps quiet desk time from occupying the archive.
    private const val MIN_NET = 30f
    private const val MIN_LAG = 30f
    private const val MIN_STUTTER = 25f

    data class Moment(
        val adapter: String,
        val severity: Float,
        val atMs: Long,
        val seenAtMs: Long,
        val summary: String,
        val driver: String
    )

    @Volatile private var appCtx: Context? = null

    fun initialize(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    fun fmtWhen(ms: Long): String = try {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ms))
    } catch (_: Throwable) { "" }

    private fun f0(v: Float): String = String.format("%.0f", v)
    private fun f1(v: Float): String = String.format("%.1f", v)

    private fun file(): File? = appCtx?.let { File(it.filesDir, "admin_worst_moments.json") }

    private fun keyOf(adapter: String): String = when (adapter) {
        AdminConfigStore.ADAPTER_NET -> "net"
        AdminConfigStore.ADAPTER_LAG -> "lag"
        AdminConfigStore.ADAPTER_STUTTER -> "stutter"
        else -> "other"
    }

    // ---------- scoring (read from the live snapshot; higher = worse) ----------

    private fun netSeverity(): Float {
        if (AdminLiveStats.probeUpdatedMs <= 0L || AdminLiveStats.rttMs <= 0f) return 0f
        val loss = if (AdminLiveStats.lossPct > 0f) AdminLiveStats.lossPct else 0f
        val tol = if (AdminLiveStats.jitterTolMs > 0) AdminLiveStats.jitterTolMs.toFloat() else 25f
        val base = if (AdminLiveStats.baselineRttMs > 0) AdminLiveStats.baselineRttMs.toFloat() else 80f
        var s = loss * 3f + (AdminLiveStats.jitterMs / tol) * 20f
        val over = AdminLiveStats.rttMs / base - 1f
        if (over > 0f) s += over * 25f
        if (AdminLiveStats.quality == "BAD") s += 15f
        return s
    }

    private fun lagSeverity(): Float {
        if (AdminLiveStats.lagUpdatedMs <= 0L) return 0f
        val stalls = AdminLiveStats.stallsPerMin.coerceAtLeast(0f)
        val spikes = AdminLiveStats.mtSpikesPerMin.coerceAtLeast(0f)
        val mt = AdminLiveStats.mtStallMs.coerceAtLeast(0f)
        val fj = AdminLiveStats.frameJitterMs.coerceAtLeast(0f)
        val stab = if (AdminLiveStats.stabilityPct >= 0f) AdminLiveStats.stabilityPct else 100f
        var s = stalls * 4f + spikes * 1.5f + mt * 0.4f + fj * 2f + (100f - stab) * 0.4f
        if (AdminLiveStats.thermal in listOf("MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")) s += 20f
        if (AdminLiveStats.lagVerdict == "CHOKING") s += 15f
        return s
    }

    private fun stutterSeverity(): Float {
        if (AdminLiveStats.stutterUpdatedMs <= 0L) return 0f
        val bpm = AdminLiveStats.sBurstsPerMin.coerceAtLeast(0f)
        var s = bpm * 4f + AdminLiveStats.sWorstMs / 8f
        when (AdminLiveStats.sState) {
            "SEIZURE" -> s += 40f
            "OSCILLATION" -> s += 20f
        }
        return s
    }

    // ---------- drivers (which engine's readings drove the score) ----------

    private fun netDriver(): String {
        val loss = if (AdminLiveStats.lossPct > 0f) AdminLiveStats.lossPct else 0f
        val tol = if (AdminLiveStats.jitterTolMs > 0) AdminLiveStats.jitterTolMs.toFloat() else 25f
        return when {
            loss > 2f && loss * 3f >= (AdminLiveStats.jitterMs / tol) * 20f -> "PacketLossProbeEngine"
            AdminLiveStats.jitterMs > tol -> "CongestionSentinelEngine"
            else -> "NetProbeEngine"
        }
    }

    private fun lagDriver(): String {
        val stalls = AdminLiveStats.stallsPerMin.coerceAtLeast(0f) * 4f
        val touch = AdminLiveStats.mtSpikesPerMin.coerceAtLeast(0f) * 1.5f +
                    AdminLiveStats.mtStallMs.coerceAtLeast(0f) * 0.4f
        val stab = if (AdminLiveStats.stabilityPct >= 0f) AdminLiveStats.stabilityPct else 100f
        val pacing = AdminLiveStats.frameJitterMs.coerceAtLeast(0f) * 2f + (100f - stab) * 0.4f
        return when {
            touch >= stalls && touch >= pacing -> "MainThreadStallEngine"
            stalls >= pacing -> "FramePacingEngine"
            else -> "LagVerdictEngine"
        }
    }

    private fun stutterDriver(): String = when (AdminLiveStats.sState) {
        "SEIZURE", "OSCILLATION" -> "BurstForensicsEngine"
        else -> "StutterPulseEngine"
    }

    // ---------- summaries (plain language, real numbers) ----------

    private fun netCleanNote(): String = try {
        if (AdminLiveStats.fresh() && AdminLiveStats.quality == "GOOD" &&
            AdminLiveStats.lossPct >= 0f && AdminLiveStats.lossPct <= 2f)
            " | your network was CLEAN at that moment - the cause was the opponent's connection or the game server, not your side"
        else ""
    } catch (_: Throwable) { "" }

    private fun netSummary(): String {
        val loss = if (AdminLiveStats.lossPct >= 0f) f0(AdminLiveStats.lossPct) + "%" else "?"
        return "ping " + f0(AdminLiveStats.rttMs) + "ms, wobble " + f0(AdminLiveStats.jitterMs) +
            "ms, lost " + loss + ", on " + AdminLiveStats.transport + " (" + AdminLiveStats.carrier + ")"
    }

    private fun lagSummary(): String {
        val stab = if (AdminLiveStats.stabilityPct >= 0f) f0(AdminLiveStats.stabilityPct) + "%" else "?"
        return "freezes " + f1(AdminLiveStats.stallsPerMin.coerceAtLeast(0f)) + "/min, touch delay " +
            f0(AdminLiveStats.mtStallMs.coerceAtLeast(0f)) + "ms, frame wobble " +
            f1(AdminLiveStats.frameJitterMs.coerceAtLeast(0f)) + "ms, steady beat " + stab +
            ", heat " + AdminLiveStats.thermal + netCleanNote()
    }

    private fun stutterSummary(): String =
        "bursts " + f0(AdminLiveStats.sBurstsPerMin.coerceAtLeast(0f)) + "/min, worst frame " +
            f0(AdminLiveStats.sWorstMs) + "ms, state " + AdminLiveStats.sState + netCleanNote()

    // ---------- the archive itself ----------

    /**
     * Score the current moment for this adapter; archive it (with Detector
     * picks computed from this exact moment) only when it is worse than
     * everything archived before. Called from every AdminLiveStats publish.
     */
    @Synchronized
    fun consider(adapter: String) {
        try {
            val sev: Float; val min: Float; val driver: String; val summary: String
            when (adapter) {
                AdminConfigStore.ADAPTER_NET -> {
                    sev = netSeverity(); min = MIN_NET; driver = netDriver(); summary = netSummary()
                }
                AdminConfigStore.ADAPTER_LAG -> {
                    sev = lagSeverity(); min = MIN_LAG; driver = lagDriver(); summary = lagSummary()
                }
                AdminConfigStore.ADAPTER_STUTTER -> {
                    sev = stutterSeverity(); min = MIN_STUTTER; driver = stutterDriver(); summary = stutterSummary()
                }
                else -> return
            }
            if (sev < min) return
            val f = file() ?: return
            val root = readRoot(f)
            val key = keyOf(adapter)
            val old = root.optJSONObject(key)
            if (old != null && sev <= old.optDouble("sev", 0.0).toFloat()) return

            // archive the Detector picks for every engine of this adapter,
            // computed from THIS moment's numbers
            val picks = JSONObject()
            for (e in AdminConfigStore.enginesFor(adapter)) {
                val ep = JSONObject()
                for (p in AdminTuningDetector.picksFor(e)) ep.put(p.key, p.value.toDouble())
                if (ep.length() > 0) picks.put(e, ep)
            }
            if (picks.length() == 0) return   // engines not fresh in this process; never archive empty

            val rec = JSONObject()
            rec.put("sev", sev.toDouble())
            rec.put("at", System.currentTimeMillis())
            rec.put("seenAt", old?.optLong("seenAt") ?: 0L)
            rec.put("summary", summary)
            rec.put("driver", driver)
            rec.put("picks", picks)
            root.put(key, rec)
            f.writeText(root.toString())
        } catch (_: Throwable) { }
    }

    /** The archived worst moment for an adapter, or null if none yet. */
    fun moment(adapter: String): Moment? = try {
        val f = file()
        if (f == null || !f.exists()) null else {
            val rec = readRoot(f).optJSONObject(keyOf(adapter))
            if (rec == null) null else Moment(
                adapter,
                rec.optDouble("sev", 0.0).toFloat(),
                rec.optLong("at"),
                rec.optLong("seenAt"),
                rec.optString("summary", ""),
                rec.optString("driver", "")
            )
        }
    } catch (_: Throwable) { null }

    /** Archived picks for one engine at the worst moment (empty if none). */
    fun archivedPicks(adapter: String, engine: String): Map<String, Float> = try {
        val f = file()
        if (f == null || !f.exists()) emptyMap() else {
            val ep = readRoot(f).optJSONObject(keyOf(adapter))
                ?.optJSONObject("picks")?.optJSONObject(engine)
            if (ep == null) emptyMap() else {
                val out = LinkedHashMap<String, Float>()
                for (k in ep.keys()) out[k] = ep.optDouble(k, 0.0).toFloat()
                out
            }
        }
    } catch (_: Throwable) { emptyMap() }

    /** Mark an adapter's archived moment as located (clears the NEW badge). */
    @Synchronized
    fun markSeen(adapter: String) {
        try {
            val f = file() ?: return
            if (!f.exists()) return
            val root = readRoot(f)
            val rec = root.optJSONObject(keyOf(adapter)) ?: return
            rec.put("seenAt", System.currentTimeMillis())
            root.put(keyOf(adapter), rec)
            f.writeText(root.toString())
        } catch (_: Throwable) { }
    }

    private fun readRoot(f: File): JSONObject = try {
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Throwable) { JSONObject() }
}
