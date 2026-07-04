from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

MAIN = ROOT / "app/src/main/java/com/assistant/MainActivity.kt"

if not MAIN.exists():
    raise SystemExit("MainActivity.kt not found")

txt = MAIN.read_text()

MARKER = "PHASE10_LIVE_RUNTIME_METRICS_MARKER"

if MARKER not in txt:

    block = '''

    // PHASE10_LIVE_RUNTIME_METRICS_MARKER

    private fun updateLiveRuntimeMetrics() {

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtRuntimeStatus
            ).text =
                if (RuntimePerformanceCoordinator.current().fpsStabilizationEnabled)
                    "Runtime • Stable"
                else
                    "Runtime • Monitoring"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtVisionStatus
            ).text =
                "Vision • Overlay " +
                if (VisionDebugOverlay.current().enabled) "ON" else "OFF"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtDiagnosticsStatus
            ).text =
                "Diagnostics • Active"
        }
    }

'''

    if "private fun synchronizeApplicationRuntime()" in txt:
        txt = txt.replace(
            "private fun synchronizeApplicationRuntime()",
            block + "\n    private fun synchronizeApplicationRuntime()",
            1
        )

    txt = re.sub(
        r"synchronizeApplicationRuntime\(\)",
        "synchronizeApplicationRuntime()\n        updateLiveRuntimeMetrics()",
        txt,
        count=1
    )

MAIN.write_text(txt)

print("PATCH COMPLETE")
