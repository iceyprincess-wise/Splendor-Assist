from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

FILE = ROOT / "app/src/main/java/com/assistant/overlay/ui/AnalyticsTheaterActivity.kt"

if not FILE.exists():
    raise SystemExit(f"Missing: {FILE}")

src = FILE.read_text()

MARKER = "PHASE10_ANALYTICS_RUNTIME_MARKER"

if MARKER not in src:

    block = """

    // PHASE10_ANALYTICS_RUNTIME_MARKER

    private fun refreshAnalyticsRuntime() {

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeDiagnosticsRegistry.refresh()
        }

        runCatching {
            RuntimeVisualizationRegistry.refresh()
        }

        runCatching {
            FPSMonitor.refresh()
        }

        runCatching {
            VisionLatencyMonitor.refresh()
        }

        runCatching {
            ConfidenceHeatmap.refresh()
        }

        runCatching {
            VisionOverlayRegistry.enableAll()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
        }
    }

"""

    if "override fun onResume()" in src:

        src = src.replace(
            "override fun onResume() {",
            "override fun onResume() {\n        refreshAnalyticsRuntime()",
            1
        )

        src = src.replace(
            "override fun onResume()",
            block + "\n    override fun onResume()",
            1
        )

    else:
        src += block

FILE.write_text(src)

print("PATCH COMPLETE")
