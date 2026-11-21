package com.assistant.diagnostic

import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FILTER ENGINE - Admin Overhead & Extremist Detector
 * 
 * Mandate:
 * 1. Determine overall app effectiveness on the device (Xiaomi Redmi 15C, Helio G81-Ultra, 4GB RAM).
 * 2. Calculate the EXACT mathematical level required for engines to be effective.
 * 3. Push engines to work harder (Extremist Mode) if they are weak/partial.
 * 4. Write diagnostic reports to /sdcard/Splendor-Assist/filter report.txt.
 * Cross-process note (SPLENDOR_P7_XPROC_DOC): telemetry is read from
 * PerformanceTelemetryRegistry (perf_device.txt, written by the :survival
 * publishers), NOT from AdapterSignalBus, which is per-process memory.
 */
object FilterEngine {

    @Volatile var isActive = false; private set
    
    // Environment health score; retain formula, do not claim FPS measurement
    @Volatile var effectivenessScore: Int? = null; private set
    
    // Aggression Multiplier: 1.0 = baseline, >1.0 = extremist push (forces engines to work harder)
    @Volatile var aggressionMultiplier: Float = 1.0f; private set
    
    private var reportFile: File? = null
    private var lastReportWrite = 0L
    private val REPORT_INTERVAL_MS = 5000L

    // F43-D3: the report file used to grow without bound (one block every
    // 5s, forever). Rotate at 1MB.
    private val MAX_REPORT_BYTES = 1_048_576L
    private const val OLD_REPORT_NAME = "filter report.old.txt"

    // F43-D2: stop() needs the worker thread. It used to be unretained,
    // so isActive could never be set false and the thread ran forever.
    private var workerThread: Thread? = null

    // F43-D7: publishFilterAggression used to rewrite perf_aggression.txt
    // on EVERY 1s tick even when the value had not changed. Readers only
    // need freshness within 15s (registry staleness window), so publish on
    // a real change (>= 0.01) or as a 10s heartbeat - same reader
    // semantics, ~10x fewer file writes.
    private var lastPublishedAggression = Float.NaN
    private var lastPublishMs = 0L
    private val AGGRO_PUBLISH_HEARTBEAT_MS = 10_000L
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun start() {
        if (isActive) return
        // F43-D6: start() had no process gate. App.onCreate gates its own
        // call to the main process, but BoosterIgnition.ensureIgnited (no
        // gate of its own) also calls start() - if that path ever ran in a
        // sub-process, a second FilterEngine would interleave writes to
        // the same report file, the exact hazard the App comment documents.
        // The main process name is the bare package name (no ':'); any
        // ':suffix' process is refused here, in the engine itself.
        if (!isMainProcess()) {
            com.assistant.diagnostic.RuntimeLogger.log("FilterEngine.start refused: not the main process", "FILTER")
            return
        }
        // SPLENDOR_P12_B3_ROOT: the hardcoded /storage/emulated/0 path
        // bypassed SplendorStorageRoot's readiness/permission gate, so a
        // not-ready start failed silently on every write. Canonical root
        // only; a not-ready start stays retryable (isActive untouched).
        if (!com.assistant.storage.SplendorStorageRoot.isReady()) {
            // F43-D5: this used to be a silent return - if both App and
            // BoosterIgnition ran before storage was ready, the engine
            // never started and nothing said why. Say why.
            com.assistant.diagnostic.RuntimeLogger.log("FilterEngine.start deferred: storage not ready (retryable)", "FILTER")
            return
        }
        val resolved = try { com.assistant.storage.SplendorStorageRoot.file("filter report.txt") } catch (_: Throwable) {
            // F43-D5: same silent-return problem as the not-ready path.
            com.assistant.diagnostic.RuntimeLogger.log("FilterEngine.start deferred: report file unresolvable (retryable)", "FILTER")
            return
        }
        
        reportFile = resolved

        // F43-D4: isActive used to be set BEFORE the worker thread was
        // created, but if thread start threw, isActive stayed true and
        // the engine was wedged "active" yet dead - start() later no-ops
        // on the isActive check. It is still set before start() (the
        // worker's first `while (isActive)` check must see true), but a
        // start failure now resets it.
        isActive = true
        val thread = Thread {
            while (isActive) {
                try {
                    calculateEffectivenessAndPush()
                    writeReport()
                } catch (t: Throwable) {
                    com.assistant.diagnostic.RuntimeLogger.log("FilterEngine error: " + t, "FILTER")
                }
                try { Thread.sleep(1000L) } catch (_: Throwable) { break }
            }
        }.apply {
            isDaemon = true
            name = "filter-engine-admin"
            priority = Thread.NORM_PRIORITY
        }
        try {
            thread.start()
        } catch (t: Throwable) {
            isActive = false
            reportFile = null
            com.assistant.diagnostic.RuntimeLogger.log("FilterEngine thread start failed: " + t, "FILTER")
            return
        }
        workerThread = thread
    }

    // F43-D2: the engine had no stop path at all - isActive was never set
    // false by anyone, so the report thread ran forever once started.
    // stop() is best-effort: the worker notices isActive within ~1s, and
    // the interrupt wakes a sleeping worker immediately.
    fun stop() {
        if (!isActive) return
        isActive = false
        val thread = workerThread
        workerThread = null
        try { thread?.interrupt() } catch (_: Throwable) {}
        try { thread?.join(2000L) } catch (_: Throwable) {}
    }

    private fun isMainProcess(): Boolean {
        return try {
            val cmdline = java.io.File("/proc/self/cmdline").readText()
                .trim('\u0000', ' ', '\n', '\r', '\t')
            // Fail open: an unreadable cmdline (essentially never on
            // Android) must not kill the main-process engine; the known
            // callers are all main-process, and the ':' check is the gate.
            cmdline.isBlank() || ':' !in cmdline
        } catch (_: Throwable) {
            true
        }
    }



    // F37-D7: the telemetry-missing test lived in two places (calc +
    // report) and could drift; one function now owns it.
    // N7: "missing" used to mean "any one field UNKNOWN", so a single
    // UNKNOWN lag verdict (capture wedged, N4) discarded five live fields
    // for 264 consecutive reports. Missing now means only "snapshot stale":
    // no publisher of any field has refreshed it in 15 s. Unknown fields
    // are reported by name and scored as no-evidence (0), never as good.
    // internal (not private) like actionFor: the f58 harness pins the
    // stale-only rule directly.
    internal fun isTelemetryMissing(updatedMs: Long, nowMs: Long): Boolean =
        nowMs - updatedMs > 15_000L

    internal fun unknownFields(
        lagVerdict: String, stutterState: String, memoryTier: String,
        memoryAvailMb: Long, thermalStatus: Int
    ): List<String> = buildList {
        if (lagVerdict == "UNKNOWN") add("lag")
        if (stutterState == "UNKNOWN") add("stutter")
        if (memoryTier == "UNKNOWN") add("memory")
        if (memoryAvailMb < 0L) add("memoryAvail")
        if (thermalStatus < 0) add("thermal")
    }

    // F43-D1: the report's action line was driven ONLY by the aggression
    // multiplier, so a red device tier printed "Nominal operation." live
    // logs proved it: filter memory CRITICAL in 605/707 reports while the
    // verdict said "Nominal operation.". The arithmetic: CRITICAL alone
    // scores 100-20=80, target multiplier 1.3, so the multiplier settles
    // at ~1.3 (<= 1.5) and the old line said Nominal. Same for JITTERY
    // alone (85 -> 1.0) and several amber combinations. The verdict must
    // reflect the worst tier, not only the multiplier: a red tier is never
    // nominal, whatever the multiplier says. Pure function of its inputs
    // so the rule is unit-testable; the multiplier logic is untouched.
    internal fun actionFor(
        telemetryMissing: Boolean, memoryTier: String,
        lagVerdict: String, stutterState: String,
        aggressionMultiplier: Float
    ): String = when {
        telemetryMissing -> "NO TELEMETRY — blind; no push applied."
        memoryTier == "CRITICAL" -> "MEMORY CRITICAL — device has no headroom; this is NOT nominal operation."
        lagVerdict == "CHOKING" || stutterState == "SEIZURE" -> "PIPELINE COLLAPSING — lag/stutter at worst tier; this is NOT nominal operation."
        aggressionMultiplier > 1.5f -> "EXTREMIST PUSH ACTIVE: Forcing engines beyond 60fps safety margins."
        memoryTier == "PRESSURE" || lagVerdict == "JITTERY" || stutterState == "OSCILLATION" || stutterState == "HICCUP" -> "UNDER STRAIN — partial pressure; watch, not nominal-calm."
        else -> "Nominal operation."
    }

    private fun calculateEffectivenessAndPush() {
        // SPLENDOR_P7_XPROC_READ_BEGIN
        // Task 2A: read the cross-process registry snapshot, NOT the
        // in-process AdapterSignalBus. The bus is a plain in-process
        // object: publishers run in :survival, FilterEngine runs in the
        // main process, so the bus here is always at its UNKNOWN/-1
        // defaults and this function used to return early on every tick
        // (142/142 report blocks UNKNOWN, multiplier stuck at 1.00x).
        // currentDeviceState() mirrors the proven currentNet() /
        // currentLoadShed() pattern: fresh in-memory value wins, else a
        // throttled read of perf_device.txt written by :survival. A
        // snapshot older than 15s is treated as missing, never as fresh.
        val state = PerformanceTelemetryRegistry.currentDeviceState()
        val nowMs = System.currentTimeMillis()
        val telemetryMissing = isTelemetryMissing(state.updatedMs, nowMs)
        // SPLENDOR_P7_XPROC_READ_END

        if (telemetryMissing) {
            effectivenessScore = null
            return
        }

        var score = 100
        score -= when (state.lagVerdict) {
            "CHOKING" -> 40
            "JITTERY" -> 15
            else -> 0
        }
        score -= when (state.stutterState) {
            "SEIZURE" -> 50
            "OSCILLATION" -> 25
            "HICCUP" -> 10
            else -> 0
        }
        // Thermal range verified: PowerManager.currentThermalStatus is
        // 0..6 (NONE..SHUTDOWN), -1 unknown. N7: a -1 used to be filtered
        // as missing above; now that UNKNOWN fields score as no-evidence
        // instead of early-returning, guard it — an unguarded -1 would
        // ADD 5 points. Unknown fields score 0.
        score -= (state.thermalStatus.coerceAtLeast(0) * 5)
        score -= when (state.memoryTier) {
            "CRITICAL" -> 20
            "PRESSURE" -> 10
            else -> 0
        }
        score = score.coerceAtLeast(0)
        effectivenessScore = score

        val targetMultiplier = when {
            score < 50 -> 2.5f
            score < 70 -> 1.8f
            score < 85 -> 1.3f
            else -> 1.0f
        }
        aggressionMultiplier = (aggressionMultiplier * 0.7f) + (targetMultiplier * 0.3f)
        // Letter A item 39: the AdapterSignalBus.publishFilterAggression
        // call that was here is REMOVED — the bus value was write-only
        // (zero readers; the bus is in-process only and the :survival
        // consumers read the registry file). The registry mirror below is
        // the live channel and is unchanged.
        // SPLENDOR_P7_XPROC_AGGRO_BEGIN
        // Task 2C: a bus publish here used to be invisible to the :survival
        // consumers (LagVerdictEngine, BurstForensicsEngine,
        // SpeedCompensationContributor read filterAggression in their own
        // process) — that is why the bus publish was removed (Letter A
        // item 39) and only this registry mirror remains. Mirror the
        // multiplier into the cross-process registry file so those readers
        // see it; stale (>15s) reads fall back to 1.0f.
        publishAggressionThrottled(aggressionMultiplier)
        // SPLENDOR_P7_XPROC_AGGRO_END
    }

    // F43-D7: throttled mirror of the aggression multiplier into the
    // cross-process registry. See the field comment above.
    private fun publishAggressionThrottled(multiplier: Float) {
        val nowMs = System.currentTimeMillis()
        if (lastPublishedAggression.isNaN() ||
            kotlin.math.abs(multiplier - lastPublishedAggression) >= 0.01f ||
            nowMs - lastPublishMs >= AGGRO_PUBLISH_HEARTBEAT_MS
        ) {
            PerformanceTelemetryRegistry.publishFilterAggression(multiplier)
            lastPublishedAggression = multiplier
            lastPublishMs = nowMs
        }
    }

    // SPLENDOR_P8_HIST_TREND_HELPERS_BEGIN
    // Trend helpers for the history-backed report lines. Pure functions
    // over the registry's parsed history lists; "n/a" when fewer than 2
    // samples exist, so an empty history never fabricates a trend.
    private fun netRttTrend(): String {
        val samples = PerformanceTelemetryRegistry.recentNetHistory(10)
        if (samples.size < 2) return "n/a (fewer than 2 samples)"
        val rtts = samples.map { it.rttMs }
        val half = rtts.size / 2
        val firstAvg = rtts.take(half).average()
        val secondAvg = rtts.drop(half).average()
        val direction = when {
            secondAvg > firstAvg * 1.1 -> "rising"
            secondAvg < firstAvg * 0.9 -> "falling"
            else -> "stable"
        }
        return "avg ${"%.0f".format(rtts.average())}ms (min ${"%.0f".format(rtts.min())} - max ${"%.0f".format(rtts.max())}) $direction"
    }

    private fun verdictCounts(samples: List<String>): String {
        if (samples.isEmpty()) return "n/a (no samples yet)"
        val counts = samples.groupingBy { it }.eachCount()
        return counts.entries.joinToString(" ") { (v, c) -> "${c}x$v" } + " (n=${samples.size})"
    }
    // SPLENDOR_P8_HIST_TREND_HELPERS_END

    private fun writeReport() {
        val now = System.currentTimeMillis()
        if (now - lastReportWrite < REPORT_INTERVAL_MS) return
        lastReportWrite = now
        
        val file = reportFile ?: return
        
        try {
            val timestamp = dateFormat.format(Date(now))
            val net = PerformanceTelemetryRegistry.currentNet()
            
            val report = buildString {
                appendLine("[$timestamp] FILTER ENGINE REPORT")
                appendLine("-----------------------------------")
                // SPLENDOR_P7_XPROC_REPORT_BEGIN
                // Task 2A: report the cross-process snapshot, not the
                // in-process bus (always UNKNOWN/-1 in this process).
                val dev = PerformanceTelemetryRegistry.currentDeviceState()
                val lag = dev.lagVerdict
                val stutter = dev.stutterState
                val memoryTier = dev.memoryTier
                val memoryAvailMb = dev.memoryAvailMb
                val telemetryMissing = isTelemetryMissing(dev.updatedMs, now)
                val unknown = unknownFields(
                    lag, stutter, memoryTier, memoryAvailMb, dev.thermalStatus
                )
                // SPLENDOR_P7_XPROC_REPORT_END
                val scoreText = if (telemetryMissing || effectivenessScore == null)
                    "ENV: UNKNOWN (telemetry missing)" else if (unknown.isNotEmpty())
                    "ENV: ${effectivenessScore}/100 (partial: unknown=${unknown.joinToString(",")})"
                else "ENV: ${effectivenessScore}/100"
                appendLine(scoreText)
                appendLine("AGGRESSION MULTIPLIER: ${"%.2f".format(aggressionMultiplier)}x")
                appendLine("")
                appendLine("DEVICE STATE (Helio G81-Ultra / 4GB RAM):")
                appendLine("  Lag Verdict    : $lag")
                appendLine("  Stutter State  : $stutter")
                appendLine("  Thermal Status : ${dev.thermalStatus}")
                appendLine("  Memory Tier    : $memoryTier (${memoryAvailMb}MB)")
                appendLine("  Battery        : ${dev.batteryLevel}% charging=${dev.batteryCharging}")
                // SPLENDOR_P7_XPROC_REPORT_LINES
                appendLine("  Network RTT    : ${net.rttMs}ms")
                appendLine("  Load Shed      : ${PerformanceTelemetryRegistry.currentLoadShed()}")
                appendLine("  Execution Brake: ${dev.executionBrake}")
                // SPLENDOR_P8_HIST_TREND_BEGIN
                // Task 3: history is read, not merely written -- the last
                // 10 samples of each channel are summarised here, so a
                // trend is visible in every report block.
                appendLine("  NET RTT TREND (last 10): ${netRttTrend()}")
                appendLine("  DISPLAY TREND (last 10): ${verdictCounts(PerformanceTelemetryRegistry.recentDisplayHistory(10).map { it.verdict })}")
                appendLine("  ACTION WINDOW (last 10): ${verdictCounts(PerformanceTelemetryRegistry.recentActionWindowHistory(10).map { it.verdict })}")
                appendLine("  LOAD SHED (last 10): ${verdictCounts(PerformanceTelemetryRegistry.recentLoadShedHistory(10).map { it.level })}")
                // SPLENDOR_P8_HIST_TREND_END
                // SPLENDOR_P7_XPROC_REPORT_BRAKE
                appendLine("")
                appendLine("FILTER ACTION:")
                // F43-D1: the action line is the worst-tier verdict, not
                // the multiplier alone (see actionFor).
                val action = actionFor(telemetryMissing, memoryTier, lag, stutter, aggressionMultiplier)
                appendLine("  $action")
                appendLine("-----------------------------------")
            }
            
            // F43-D3: rotate the report file instead of appending forever.
            // A failed rotate must never lose the block: on failure, append
            // to the existing file as before.
            try {
                if (file.exists() && file.length() > MAX_REPORT_BYTES) {
                    val old = file.parentFile?.let { File(it, OLD_REPORT_NAME) }
                    if (old != null) {
                        if (old.exists()) old.delete()
                        file.renameTo(old)
                    }
                }
            } catch (_: Throwable) {
                // Rotation is best-effort; the block below still appends.
            }
            file.appendText(report)
        } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("FilterEngine report write failed: " + t, "FILTER") }
    }
}
