package com.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.assistant.audit.SelfAuditRegistry
import com.assistant.compliance.ComplianceState
import com.assistant.diagnostic.InAppLiveDisplay
import com.assistant.diagnostic.RuntimeMetricsRegistry
import com.assistant.survival.AdapterHealthCounts
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

@android.annotation.SuppressLint("StaticFieldLeak")
object DashboardInjector {

    private const val DASHBOARD_TAG = "splendor_dashboard_overlay"

    private var activeContainer: LinearLayout? = null
    private var activeHandler: Handler? = null
    private var activeRunnable: DashboardRefreshRunnable? = null
    
    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-DashboardPoll").apply { priority = Thread.MIN_PRIORITY }
    }

    fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)

        // Cleanup previous state
        detach()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(40, 40, 40, 120)
            tag = DASHBOARD_TAG
        }

        val title = TextView(activity).apply {
            text = "SPLENDOR ASSIST PRO"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val runtime = TextView(activity).apply {
            // F36-D1: AdapterHealthRegistry is NOT deleted - it lives at
            // core/src/main/java/com/assistant/diagnostic/registry/
            // AdapterHealthRegistry.kt and holds per-adapter snapshots
            // (getAllLive(), effectiveStatus()). This label needs only the
            // fleet lifecycle state (COLD/WARMING/READY/DEGRADED) plus the
            // ignited flag, which is what BoosterIgnition.fleetSnapshot()
            // returns; per-adapter health is already shown by the refresh
            // runnable below via AdapterHealthCounts.combinedSnapshot()
            // (ledger + counts, fed by the watchdog), so this
            // function does not read the registry directly.
            val snapshot = BoosterIgnition.fleetSnapshot()
            val state = snapshot["state"] ?: "COLD"
            val ignited = snapshot["ignited"] ?: false
            text = "Fleet State: $state (Ignited: $ignited)"
            textSize = 14f
            setTextColor(Color.GREEN)
        }

        val metrics = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.YELLOW)
        }

        val status = TextView(activity).apply {
            text = ComplianceState.summary(activity)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.CYAN)
        }

        val launch = Button(activity).apply {
            text = "ACTIVATE ALL ADAPTERS"
            setOnClickListener {
                // P0 FIX: IgnitionEngine.ignite() returns Unit. Do not assign or check as Boolean.
                IgnitionEngine.ignite(activity.applicationContext)
                
                // P0 FIX: Immediately refresh UI using BoosterIgnition data
                val snapshot = BoosterIgnition.fleetSnapshot()
                val state = snapshot["state"] ?: "WARMING"
                val ignited = snapshot["ignited"] ?: false
                runtime.text = "Fleet State: $state (Ignited: $ignited)"
                status.text = ComplianceState.summary(activity)
            }
        }

        val adapterStatus = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            text = "Adapters: Waiting for ignition..."
        }

        container.addView(title)
        container.addView(runtime)
        container.addView(metrics)
        container.addView(adapterStatus)
        container.addView(status)
        container.addView(launch)

        root.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        activeContainer = container
        
        val handler = Handler(Looper.getMainLooper())
        activeHandler = handler
        
        val runnable = DashboardRefreshRunnable(
            WeakReference(activity), 
            WeakReference(metrics), 
            WeakReference(status), 
            WeakReference(runtime),
            WeakReference(adapterStatus),
            handler
        )
        activeRunnable = runnable
        handler.post(runnable)
    }

    fun detach() {
        activeHandler?.removeCallbacksAndMessages(null)
        activeHandler = null
        activeRunnable = null
        
        activeContainer?.let { previous ->
            try {
                (previous.parent as? ViewGroup)?.removeView(previous)
            } catch (_: Exception) {}
        }
        activeContainer = null
    }

    private class DashboardRefreshRunnable(
        private val activityRef: WeakReference<Activity>,
        private val metricsRef: WeakReference<TextView>,
        private val statusRef: WeakReference<TextView>,
        private val runtimeRef: WeakReference<TextView>,
        private val adapterStatusRef: WeakReference<TextView>,
        private val handler: Handler
    ) : Runnable {
        override fun run() {
            val activity = activityRef.get()
            val metricsView = metricsRef.get()
            val statusView = statusRef.get()
            val runtimeView = runtimeRef.get()
            val adapterStatusView = adapterStatusRef.get()

            if (activity == null || metricsView == null || statusView == null || runtimeView == null || adapterStatusView == null) {
                return 
            }

            // Offload heavy string building to background thread
            bgExecutor.execute {
                val metricsText = buildString {
                    append(RuntimeMetricsRegistry.snapshot()).append("\n\n")
                    // AHC-D2 (batch 5): one atomic read of the adapter
                    // ledger + counts (ledger first, the old display
                    // order) instead of two separate snapshots that could
                    // straddle a watchdog scan.
                    append(AdapterHealthCounts.combinedSnapshot()).append("\n\n")
                    append(SelfAuditRegistry.snapshot()).append("\n\n")
                    // No62-D18 + No62-D8: the overlay's liveness
                    // (OverlaySurvivalRegistry.state(), wired this round
                    // — it had zero readers before) and the live
                    // self-mask rect count (OverlaySelfMask.count(),
                    // likewise unread before) share one line next to the
                    // audit snapshot. Both are point-in-time reads on
                    // the background refresh thread; count() reads a
                    // ConcurrentHashMap size and state() is @Synchronized.
                    append("Overlay survival: ").append(com.assistant.survival.OverlaySurvivalRegistry.state())
                        .append(" | selfMask rects: ").append(com.assistant.vision.OverlaySelfMask.count()).append("\n\n")
                    append(com.assistant.recovery.RecoveryMetricsRegistry.snapshot())
                    // No63-D1/D27/D28: the accessibility-survival state
                    // (wired this round — it had zero readers before),
                    // the latest keeper decision (written only, no reader
                    // before) and the keeper metrics snapshot (likewise
                    // unread before) join the metrics block. All three are
                    // point-in-time reads on the background refresh
                    // thread; the registries are @Volatile/@Synchronized
                    // and snapshot() reads AtomicLongs.
                    append("\nAccessibility survival: ").append(com.assistant.survival.AccessibilitySurvivalRegistry.state()).append("\n")
                    val gkDecision = com.assistant.overlay.interceptor.GoalkeeperDecisionRegistry.latestDecision
                    append("GK decision: ").append(if (gkDecision == null) "none" else "${gkDecision.threat} ${gkDecision.zone} ${gkDecision.direction} pri=${gkDecision.priority}").append("\n")
                    append(com.assistant.overlay.interceptor.GoalkeeperMetricsRegistry.snapshot()).append("\n")
                    // L16 (batch 1): the ball-telemetry snapshot's first
                    // reader (Q68-60: ballTelemetryRuntimeSnapshot() was
                    // unwired to any reader). Point-in-time read on the
                    // background refresh thread, like the registries above.
                    val ballSnap = com.assistant.BallTelemetryBridge.ballTelemetryRuntimeSnapshot()
                    append("Ball telemetry: published=").append(ballSnap["published"])
                        .append(" skippedUndetected=").append(ballSnap["skippedUndetected"])
                        .append(" lastConfidence=").append(ballSnap["lastConfidence"])
                        .append(" lastUpdatedMs=").append(ballSnap["lastUpdatedMs"]).append("\n")
                    // Item 13 (half-effect): the runtime snapshot states
                    // (RuntimePerformanceState / RuntimeDiagnosticsState /
                    // VisionLatencyMonitorState) were write-only — their
                    // first reader. Point-in-time reads on the background
                    // refresh thread, like the registries above.
                    val perfSnap = com.assistant.RuntimePerformanceCoordinator.current()
                    val diagSnap = com.assistant.RuntimeDiagnosticsRegistry.current()
                    val latSnap = com.assistant.VisionLatencyMonitor.current()
                    append("Runtime: fps=").append(perfSnap.fpsMonitor.fps)
                        .append(" latencyMs=").append(latSnap.latencyMs)
                        .append(" measured=").append(latSnap.hasMeasurement)
                        .append(" latencyUpdatedMs=").append(latSnap.lastUpdatedMs)
                        .append(" heat=").append(perfSnap.confidenceHeatmap.confidence)
                        .append(" ts=").append(perfSnap.timestampMs).append("\n")
                    // Phase I (I-5): first live readers for five
                    // previously write-only input counters — the latency
                    // measurement/lag-event counts, the priority reapply
                    // count, the last vsync delay and the thermal status
                    // string. Point-in-time reads on the background
                    // refresh thread, like the lines above.
                    append("Input: latencyMeasurements=").append(com.assistant.InputLatencyEngine.measurements)
                        .append(" lagEvents=").append(com.assistant.InputLatencyEngine.lagEvents)
                        .append(" priorityReapplies=").append(com.assistant.InputPriorityEngine.reapplyCount)
                        .append(" vsyncLastDelayMs=").append(com.assistant.InputVsyncEliminatorEngine.lastFrameDelayMs)
                        .append(" thermal=").append(com.assistant.InputThermalEliminatorEngine.thermalStatus).append("\n")
                    // F3 (Letter F): first live readers for three
                    // previously write-only outputs — ForegroundGate
                    // stats, FilterEngine score, FreeBooster counters.
                    // Point-in-time reads on the background refresh
                    // thread, like the registries above.
                    append("Foreground gate: ").append(com.assistant.vision.ForegroundGate.stats()).append("\n")
                    append("Filter: active=").append(com.assistant.diagnostic.FilterEngine.isActive).append(" score=").append(com.assistant.diagnostic.FilterEngine.effectivenessScore).append(" aggression=").append(com.assistant.diagnostic.FilterEngine.aggressionMultiplier).append("x\n")
                    append("Booster: inMatch=").append(com.assistant.FreeBoosterEngine.isInMatch()).append(" matches=").append(com.assistant.FreeBoosterEngine.matchesBoosted).append(" sheds=").append(com.assistant.FreeBoosterEngine.shedsApplied).append(" droppedLines=").append(com.assistant.FreeBoosterEngine.routineLinesDropped).append(" droppedBytes=").append(com.assistant.FreeBoosterEngine.routineBytesDropped).append("\n")
                    append("Vision config: enabled=").append(diagSnap.visionConfiguration.enabled)
                        .append(" blob=").append(diagSnap.visionConfiguration.blobThreshold)
                        .append(" tflite=").append(diagSnap.visionConfiguration.trainedDetectionEnabled)
                        .append(" trackingEnabled=").append(diagSnap.trackingConfiguration.enabled).append("\n")
                    // GER-D6 (batch 5): the last-accepted-contribution
                    // map was write-only — its first reader. Compact
                    // engine:action pairs (52 engines max, one line).
                    val lastContrib = com.assistant.runtime.GameplayEngineRegistry.lastContributionSnapshot()
                    append("Last contributions: ").append(if (lastContrib.isEmpty()) "none yet" else lastContrib.entries.sortedBy { it.key }.joinToString(", ") { (name, c) -> "$name:${c.actionClass}" }).append("\n")
                    // O37-5 (batch 5): the dropped-write counter was
                    // session-header + logcat only — its first in-app
                    // reader. Non-zero means log lines were lost.
                    append("Dropped log writes: ").append(com.assistant.diagnostic.RuntimeLogger.droppedWriteCount()).append("\n")
                    // Phase L (L-7): LogMirror.droppedCount() had zero
                    // readers — the dropped-records counter was maintained
                    // on every loss and read by nobody. First live reader.
                    // Point-in-time read on the background refresh thread,
                    // like the line above. Non-zero means log lines were
                    // lost (queue full or pre-init).
                    append("LogMirror dropped: ").append(com.assistant.diagnostic.LogMirror.droppedCount()).append("\n")
                    // Q68-51 (batch 5b): eight SmartAssistMetrics snapshot
                    // fns had zero readers — their first readers. One
                    // compact line each; the maps are small counter maps,
                    // read point-in-time on the background refresh thread.
                    append("GK shadow: ").append(com.assistant.SmartAssistMetrics.goalkeeperShadowRuntimeSnapshot()).append("\n")
                    append("Gameplay downstream: ").append(com.assistant.SmartAssistMetrics.gameplayDownstreamRuntimeSnapshot()).append("\n")
                    append("Gameplay amplification: ").append(com.assistant.SmartAssistMetrics.gameplayAmplificationRuntimeSnapshot()).append("\n")
                    append("Magnetic feet: ").append(com.assistant.SmartAssistMetrics.magneticFeetRuntimeSnapshot()).append("\n")
                    append("Crossing lane: ").append(com.assistant.SmartAssistMetrics.crossingLaneRuntimeSnapshot()).append("\n")
                    append("Magnetic feet activation: ").append(com.assistant.SmartAssistMetrics.magneticFeetActivationRuntimeSnapshot()).append("\n")
                    append("Gameplay activation: ").append(com.assistant.SmartAssistMetrics.gameplayActivationRuntimeSnapshot()).append("\n")
                    append("Controller entry: ").append(com.assistant.SmartAssistMetrics.controllerEntryRuntimeSnapshot()).append("\n")
                    // Q62-2 (batch 5b): OverlaySelfMask.mask() — the rect
                    // LIST itself — had zero readers (only count() was
                    // read, above). The rects the mask is currently
                    // suppressing, one line; the list is small (one rect
                    // per masked UI element).
                    append("Self-mask rects: ").append(com.assistant.vision.OverlaySelfMask.mask().joinToString(", ")).append("\n")
                    // Phase M (M-9): MachineLedger.latest()/sampleCount()
                    // had zero production readers — the heap/CPU samples
                    // were collected into a ring and never read. First
                    // live reader: this line. Point-in-time reads on the
                    // background refresh thread, like the lines above;
                    // latest()/sampleCount() are @Synchronized.
                    val machine = com.assistant.diagnostic.MachineLedger.latest()
                    append("Machine: ").append(if (machine == null) "no samples yet" else "heapFree=${machine.freeMemoryBytes / 1048576L}MB heapTotal=${machine.totalMemoryBytes / 1048576L}MB heapMax=${machine.maxMemoryBytes / 1048576L}MB cpus=${machine.availableProcessors} samples=${com.assistant.diagnostic.MachineLedger.sampleCount()}").append("\n")
                    // Phase M (M-10): MemoryAttributionEngine's six
                    // fields had zero readers outside logLine — the
                    // breakdown of which process ate the RAM only ever
                    // reached the log. First live reader: this line,
                    // from lastAttribution (set by MemoryMonitorEngine
                    // on every avail drop). "none yet" before the first
                    // drop; the log line stays as the proof record.
                    val memAttr = com.assistant.MemoryAttributionEngine.lastAttribution
                    append("Memory attribution: ").append(if (memAttr == null) "none yet" else "drop=${memAttr.availDropMb}MB app=${memAttr.appMb}MB survival=${memAttr.survivalMb}MB game=${memAttr.gameMb}MB system=${memAttr.systemMb}MB" + if (memAttr.unknown) " unknown=1" else "").append("\n")
                }
                
                val statusText = ComplianceState.summary(activity)
                
                // P0 FIX: Fetch live fleet state from BoosterIgnition
                val snapshot = BoosterIgnition.fleetSnapshot()
                val state = snapshot["state"] as? String ?: "COLD"
                val ignited = snapshot["ignited"] as? Boolean ?: false
                val degraded = snapshot["fleetDegraded"] as? Boolean ?: false
                
                val runtimeText = "Fleet State: $state (Ignited: $ignited)"
                val adapterText = if (degraded) "⚠️ FLEET DEGRADED (>2 Offline)" else "✅ Fleet Healthy"

                // Post only the final text to Main Thread
                handler.post {
                    if (metricsView.isAttachedToWindow) {
                        metricsView.text = metricsText
                        statusView.text = statusText
                        runtimeView.text = runtimeText
                        adapterStatusView.text = adapterText
                        
                        // Color code the runtime text based on state
                        when (state) {
                            "READY" -> runtimeView.setTextColor(Color.GREEN)
                            "DEGRADED" -> runtimeView.setTextColor(Color.RED)
                            "WARMING" -> runtimeView.setTextColor(Color.YELLOW)
                            else -> runtimeView.setTextColor(Color.WHITE)
                        }
                        
                        // no59 ITEM 4: STOPPED — the 1s live refresh no
                        // longer re-arms. InAppLiveDisplay is the single
                        // switch (default OFF): with it off this runnable
                        // runs exactly once, when attach() posts it, and
                        // the overlay then shows that one snapshot until
                        // the user re-attaches or taps ACTIVATE. File
                        // logging is untouched by this flag.
                        if (InAppLiveDisplay.liveRefreshAllowed()) {
                            handler.postDelayed(this@DashboardRefreshRunnable, 1000L)
                        }
                    }
                }
            }
        }
    }
}
