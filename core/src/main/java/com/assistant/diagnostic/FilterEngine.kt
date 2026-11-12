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
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun start() {
        if (isActive) return
        isActive = true
        
        // Initialize report file in external storage root for user accessibility
        val splendorDir = File("/storage/emulated/0/Splendor-Assist")
        if (!splendorDir.exists()) splendorDir.mkdirs()
        reportFile = File(splendorDir, "filter report.txt")
        
        Thread {
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
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isActive = false
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
        val telemetryMissing = state.lagVerdict == "UNKNOWN" || state.stutterState == "UNKNOWN" ||
            state.memoryTier == "UNKNOWN" || state.memoryAvailMb < 0L || state.thermalStatus < 0 ||
            nowMs - state.updatedMs > 15_000L
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
        score -= (state.thermalStatus * 5)
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
        AdapterSignalBus.publishFilterAggression(aggressionMultiplier)
        // SPLENDOR_P7_XPROC_AGGRO_BEGIN
        // Task 2C: the bus publish above is invisible to the :survival
        // consumers (LagVerdictEngine, BurstForensicsEngine,
        // SpeedCompensationContributor read filterAggression in their own
        // process). Mirror the multiplier into the cross-process registry
        // file so those readers see it; stale (>15s) reads fall back to 1.0f.
        PerformanceTelemetryRegistry.publishFilterAggression(aggressionMultiplier)
        // SPLENDOR_P7_XPROC_AGGRO_END
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
                val telemetryMissing = lag == "UNKNOWN" || stutter == "UNKNOWN" ||
                    memoryTier == "UNKNOWN" || memoryAvailMb < 0L || dev.thermalStatus < 0 ||
                    now - dev.updatedMs > 15_000L
                // SPLENDOR_P7_XPROC_REPORT_END
                val scoreText = if (telemetryMissing || effectivenessScore == null)
                    "ENV: UNKNOWN (telemetry missing)" else "ENV: ${effectivenessScore}/100"
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
                val action = if (aggressionMultiplier > 1.5f) "EXTREMIST PUSH ACTIVE: Forcing engines beyond 60fps safety margins." else "Nominal operation."
                appendLine("  $action")
                appendLine("-----------------------------------")
            }
            
            file.appendText(report)
        } catch (_: Throwable) {}
    }
}
