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
 */
object FilterEngine {

    @Volatile var isActive = false; private set
    
    // Effectiveness Score: 100 = perfect 60fps, <100 = struggling
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
        val lag = AdapterSignalBus.lagVerdict
        val stutter = AdapterSignalBus.stutterState
        val thermal = AdapterSignalBus.thermalStatus
        val memTier = AdapterSignalBus.memoryTier
        val memoryAvailMb = AdapterSignalBus.memoryAvailMb
        val telemetryMissing = lag == "UNKNOWN" || stutter == "UNKNOWN" ||
            memTier == "UNKNOWN" || memoryAvailMb < 0L || thermal < 0

        if (telemetryMissing) {
            effectivenessScore = null
            return
        }

        var score = 100
        score -= when (lag) {
            "CHOKING" -> 40
            "JITTERY" -> 15
            else -> 0
        }
        score -= when (stutter) {
            "SEIZURE" -> 50
            "OSCILLATION" -> 25
            "HICCUP" -> 10
            else -> 0
        }
        score -= (thermal * 5)
        score -= when (memTier) {
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
    }

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
                val lag = AdapterSignalBus.lagVerdict
                val stutter = AdapterSignalBus.stutterState
                val memoryTier = AdapterSignalBus.memoryTier
                val memoryAvailMb = AdapterSignalBus.memoryAvailMb
                val telemetryMissing = lag == "UNKNOWN" || stutter == "UNKNOWN" ||
                    memoryTier == "UNKNOWN" || memoryAvailMb < 0L || AdapterSignalBus.thermalStatus < 0
                val scoreText = if (telemetryMissing || effectivenessScore == null)
                    "score: UNKNOWN (telemetry missing)" else "score: ${effectivenessScore}/100"
                appendLine(scoreText)
                appendLine("AGGRESSION MULTIPLIER: ${"%.2f".format(aggressionMultiplier)}x")
                appendLine("")
                appendLine("DEVICE STATE (Helio G81-Ultra / 4GB RAM):")
                appendLine("  Lag Verdict    : ${AdapterSignalBus.lagVerdict}")
                appendLine("  Stutter State  : ${AdapterSignalBus.stutterState}")
                appendLine("  Thermal Status : ${AdapterSignalBus.thermalStatus}")
                appendLine("  Memory Tier    : $memoryTier (${memoryAvailMb}MB)")
                appendLine("  Battery        : ${AdapterSignalBus.batteryLevel}% charging=${AdapterSignalBus.batteryCharging}")
                appendLine("  Network RTT    : ${net.rttMs}ms")
                appendLine("  Load Shed      : ${PerformanceTelemetryRegistry.currentLoadShed()}")
                appendLine("  Execution Brake: ${AdapterSignalBus.executionBrake}")
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
