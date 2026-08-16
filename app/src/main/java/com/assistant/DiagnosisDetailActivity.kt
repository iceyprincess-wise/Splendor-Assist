package com.assistant

import com.assistant.storage.SplendorStorageRoot

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
        "MagneticFeetEngine" -> "Touch retention, interception resistance and possession control in the new contributor runtime."
        "GameplayDecisionEngine" -> "Legacy gameplay decision engine diagnostics alongside the new RuntimeDecisionLoop truth surface."
        "CrossingLaneAnalysisEngine" -> "Crossing lane viability and confidence feeding RuntimeFrame assembly."
        "SmartAssistMetrics" -> "Runtime truth surface for coordinator, health, frame, decision, contribution and execution state."
        "SmartAssistControlRoomActivity" -> "Control room UI displaying runtime truth instead of legacy engine-local labels."
        "RuntimeLogger" -> "Runtime, execution, telemetry, heartbeat and field-test evidence."
        else -> "Unknown engine."
    }

    private fun chain(name: String): String = when (name) {
        "MagneticFeetEngine" -> "RuntimeFrame -> MagneticFeetContributor -> RuntimeDecisionLoop -> registry/decision surfaces."
        "GameplayDecisionEngine" -> "Legacy ActiveGestureController path; runtime truth now flows through RuntimeDecisionLoop."
        "CrossingLaneAnalysisEngine" -> "VisionCore -> CrossingLaneAnalysisEngine -> FrameAssembler -> RuntimeFrame -> contributors."
        "SmartAssistMetrics" -> "RuntimeCoordinator/FrameAssembler/RuntimeDecisionLoop/ContributionRegistry/GestureExecutionAuthority -> SmartAssistMetrics -> Control Room + Diagnosis Room."
        "SmartAssistControlRoomActivity" -> "Dashboard/control-room navigation -> SmartAssistControlRoomActivity -> runtime truth surfaces."
        "RuntimeLogger" -> "App/components -> RuntimeLogger -> app-owned log segments and forensic files."
        else -> "No chain."
    }

    private fun stateFor(name: String): String = when (name) {
        "MagneticFeetEngine" -> MagneticFeetEngine.magneticFeetSnapshot()?.toString() ?: "No MagneticFeet snapshot yet."
        "GameplayDecisionEngine" -> "decision=" + com.assistant.adapter.smartassist.RuntimeDecisionLoop.decisionRuntimeSnapshot()
        "CrossingLaneAnalysisEngine" -> "crossing=" + SmartAssistMetrics.crossingLaneRuntimeSnapshot() + "\nframe=" + com.assistant.adapter.smartassist.FrameAssembler.frameRuntimeSnapshot()
        "SmartAssistMetrics" -> buildString {
            appendLine("runtime=" + com.assistant.adapter.smartassist.RuntimeCoordinator.runtimeState())
            appendLine("health=" + com.assistant.adapter.smartassist.RuntimeHealthMonitor.runtimeHealthSnapshot())
            appendLine("frame=" + com.assistant.adapter.smartassist.FrameAssembler.frameRuntimeSnapshot())
            appendLine("decision=" + com.assistant.adapter.smartassist.RuntimeDecisionLoop.decisionRuntimeSnapshot())
            appendLine("contributions=" + com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot())
            appendLine("execution=" + com.assistant.adapter.smartassist.GestureExecutionAuthority.executionRuntimeSnapshot())
            appendLine("registry=" + com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot())
        }
        "SmartAssistControlRoomActivity" -> "Displays runtime, health, frame, decision, contribution, execution and registry truth."
        "RuntimeLogger" -> "Owns runtime logs, hourly retention segments, execution, telemetry, heartbeat and field-test traces."
        else -> "Unknown."
    }

    private fun metricsFor(name: String): String = when (name) {
        "MagneticFeetEngine" -> buildString {
            appendLine("magneticFeet=" + SmartAssistMetrics.magneticFeetRuntimeSnapshot())
            appendLine("registry=" + com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot())
            appendLine("decision=" + com.assistant.adapter.smartassist.RuntimeDecisionLoop.decisionRuntimeSnapshot())
        }
        "GameplayDecisionEngine" -> buildString {
            appendLine("decision=" + com.assistant.adapter.smartassist.RuntimeDecisionLoop.decisionRuntimeSnapshot())
            appendLine("legacyActivation=" + SmartAssistMetrics.gameplayActivationRuntimeSnapshot())
        }
        "CrossingLaneAnalysisEngine" -> buildString {
            appendLine("crossing=" + SmartAssistMetrics.crossingLaneRuntimeSnapshot())
            appendLine("frame=" + com.assistant.adapter.smartassist.FrameAssembler.frameRuntimeSnapshot())
        }
        "SmartAssistMetrics" -> buildString {
            appendLine("runtime=" + com.assistant.adapter.smartassist.RuntimeCoordinator.runtimeState())
            appendLine("health=" + com.assistant.adapter.smartassist.RuntimeHealthMonitor.runtimeHealthSnapshot())
            appendLine("frame=" + com.assistant.adapter.smartassist.FrameAssembler.frameRuntimeSnapshot())
            appendLine("decision=" + com.assistant.adapter.smartassist.RuntimeDecisionLoop.decisionRuntimeSnapshot())
            appendLine("contributions=" + com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot())
            appendLine("execution=" + com.assistant.adapter.smartassist.GestureExecutionAuthority.executionRuntimeSnapshot())
            appendLine("registry=" + com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot())
        }
        "SmartAssistControlRoomActivity" -> "Control Room consumes runtime truth surfaces and displays live runtime state."
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
        val forensicsDir = SplendorStorageRoot.subdirectory("Forensics")
        files += File(forensicsDir, "execution_chain.log")
        files += File(forensicsDir, "telemetry.log")
        files += File(forensicsDir, "heartbeat.log")
        files += File(forensicsDir, "fieldtest.log")
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
