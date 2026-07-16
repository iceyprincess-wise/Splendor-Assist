package com.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.adapter.smartassist.CrossingLaneAnalysisEngine
import com.assistant.adapter.smartassist.GameplayDecisionEngine
import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.adapter.smartassist.RuntimeDiagnosticsRegistry
import com.assistant.adapter.smartassist.SmartAssistMetrics
import com.assistant.diagnostic.RuntimeLogger
import java.io.File

class DiagnosisDetailActivity : AppCompatActivity() {
    private lateinit var detail: TextView
    private lateinit var engine: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeLogger.reconcileExpired()
        RuntimeDiagnosticsRegistry.refresh()
        SmartAssistMetrics.runGameplayHeartbeat("DiagnosisDetailActivity opened")
        engine = intent.getStringExtra(EXTRA_ENGINE) ?: "RuntimeLogger"
        setContentView(buildPage())
        render()
    }

    private fun buildPage(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        root.addView(TextView(this).apply {
            text = engine
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        detail = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }

        root.addView(ScrollView(this).apply { addView(detail) }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@DiagnosisDetailActivity).apply {
                text = "Copy Engine Log"
                setOnClickListener { copy(detail.text.toString()) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@DiagnosisDetailActivity).apply {
                text = "Back"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        return root
    }

    private fun render() {
        detail.text = buildString {
            appendLine("=== $engine ===")
            appendLine("Purpose: ${purpose(engine)}")
            appendLine("Live chain: ${chain(engine)}")
            appendLine()
            appendLine("Current state / snapshot:")
            appendLine(stateFor(engine))
            appendLine()
            appendLine("Metrics:")
            appendLine(metricsFor(engine))
            appendLine()
            appendLine("Activation diagnostics:")
            appendLine(activationFor(engine))
            appendLine()
            appendLine("Gameplay heartbeat diagnostics:")
            appendLine(SmartAssistMetrics.gameplayHeartbeatRuntimeSnapshot())
            appendLine()
            appendLine("Controller entry diagnostics:")
            appendLine(SmartAssistMetrics.controllerEntryRuntimeSnapshot())
            appendLine()
            appendLine("Bus execution diagnostics:")
            appendLine(SmartAssistMetrics.busExecutionRuntimeSnapshot())
            appendLine()
            appendLine("Goalkeeper shadow diagnostics:")
            appendLine(SmartAssistMetrics.goalkeeperShadowRuntimeSnapshot())
            appendLine()
            appendLine("Runtime traces:")
            appendLine(filteredLogs(engine).ifBlank { "No app-owned trace found yet for this engine." })
            appendLine()
            appendLine("Diagnosis truth boundary:")
            appendLine("This page replaces normal ADB logcat checking for app-owned evidence by showing snapshots, metrics, call chains, and RuntimeLogger-owned traces.")
        }
    }

    private fun purpose(name: String): String = when (name) {
        "MagneticFeetEngine" -> "Touch retention, interception resistance and possession control."
        "GameplayDecisionEngine" -> "Adaptive mode, decision stability and gameplay authority."
        "CrossingLaneAnalysisEngine" -> "Crossing lane viability, target and confidence."
        "SmartAssistMetrics" -> "Request, execution, trajectory and engine diagnostic counters."
        "SmartAssistControlRoomActivity" -> "Control room UI and runtime metric display."
        "RuntimeLogger" -> "Runtime, execution, telemetry, heartbeat and field-test evidence."
        else -> "Unknown engine."
    }

    private fun chain(name: String): String = when (name) {
        "MagneticFeetEngine" -> "ActiveGestureController -> MagneticFeetEngine.stabilize -> pass/engagement/defense consumers -> metrics."
        "GameplayDecisionEngine" -> "ActiveGestureController -> GameplayDecisionEngine -> DecisionResult -> CentralExecutionBus -> gesture execution."
        "CrossingLaneAnalysisEngine" -> "VisionCore -> CrossingLaneAnalysisEngine -> Phase3WorldStateStore -> TrueTargetPassingEngine -> ActiveGestureController."
        "SmartAssistMetrics" -> "Engines/accessibility/controller -> SmartAssistMetrics -> Control Room + Diagnosis Room."
        "SmartAssistControlRoomActivity" -> "Dashboard/control-room navigation -> SmartAssistControlRoomActivity -> SmartAssistMetrics."
        "RuntimeLogger" -> "App/components -> RuntimeLogger -> app-owned log segments and forensic files."
        else -> "No chain."
    }

    private fun stateFor(name: String): String = when (name) {
        "MagneticFeetEngine" -> MagneticFeetEngine.magneticFeetSnapshot()?.toString() ?: "No MagneticFeet snapshot yet."
        "GameplayDecisionEngine" -> "downstream=${GameplayDecisionEngine.gameplayDownstreamSnapshot()}\namplification=${GameplayDecisionEngine.gameplayAmplificationSnapshot()}"
        "CrossingLaneAnalysisEngine" -> CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()?.toString() ?: "No CrossingLane snapshot yet."
        "SmartAssistMetrics" -> "submitted=${SmartAssistMetrics.requestsSubmitted.get()}, executed=${SmartAssistMetrics.requestsExecuted.get()}, trajectory=${SmartAssistMetrics.trajectoryProduced.get()}"
        "SmartAssistControlRoomActivity" -> "Owns visible SmartAssist runtime/metrics UI. Latest metrics are read from SmartAssistMetrics."
        "RuntimeLogger" -> "Owns runtime logs, hourly retention segments, execution, telemetry, heartbeat and field-test traces."
        else -> "Unknown."
    }

    private fun metricsFor(name: String): String = when (name) {
        "MagneticFeetEngine" -> SmartAssistMetrics.magneticFeetRuntimeSnapshot().toString()
        "GameplayDecisionEngine" -> SmartAssistMetrics.gameplayDownstreamRuntimeSnapshot().toString() + "\n" + SmartAssistMetrics.gameplayAmplificationRuntimeSnapshot()
        "CrossingLaneAnalysisEngine" -> SmartAssistMetrics.crossingLaneRuntimeSnapshot().toString()
        "SmartAssistMetrics" -> "submitted=${SmartAssistMetrics.requestsSubmitted.get()}, executed=${SmartAssistMetrics.requestsExecuted.get()}, trajectory=${SmartAssistMetrics.trajectoryProduced.get()}"
        "SmartAssistControlRoomActivity" -> "Control Room consumes SmartAssistMetrics and displays runtime status."
        "RuntimeLogger" -> "RuntimeLogger source files are tailed below from app-owned locations."
        else -> "No metrics."
    }

    private fun activationFor(name: String): String = when (name) {
        "MagneticFeetEngine" -> SmartAssistMetrics.magneticFeetActivationRuntimeSnapshot().toString()
        "GameplayDecisionEngine" -> SmartAssistMetrics.gameplayActivationRuntimeSnapshot().toString()
        "CrossingLaneAnalysisEngine" -> "Crossing lane sequence changes prove VisionCore is repeatedly calling analyze()."
        else -> "No dedicated activation diagnostics for this component yet."
    }

    private fun filteredLogs(name: String): String {
        val keys = listOf(name, name.removeSuffix("Engine"), "SMART_ASSIST", "DIAGNOSTIC", "RUNTIME")
        val files = mutableListOf<File>()
        files += File(filesDir, "runtime_diagnostic.txt")
        File(filesDir, "runtime_hour_segments").takeIf { it.isDirectory }?.listFiles()?.sortedByDescending { it.lastModified() }?.take(3)?.let { files += it }
        files += File("/storage/emulated/0/SplendorAssist/Forensics/execution_chain.log")
        files += File("/storage/emulated/0/SplendorAssist/Forensics/telemetry.log")
        files += File("/storage/emulated/0/SplendorAssist/Forensics/heartbeat.log")
        files += File("/storage/emulated/0/SplendorAssist/Forensics/fieldtest.log")
        return files.flatMap { file -> runCatching { file.readLines().takeLast(300) }.getOrDefault(emptyList()) }
            .filter { line -> keys.any { key -> line.contains(key, ignoreCase = true) } }
            .takeLast(160)
            .joinToString("\n")
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Engine Diagnosis", text))
        Toast.makeText(this, "Engine diagnosis copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ENGINE = "engine"
    }
}
