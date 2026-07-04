from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

FILES = [
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt",
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt",
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt",
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/FutureRoomsActivity.kt",
]

MARKER = "PHASE10_CONTROLROOM_RUNTIME_MARKER"

for f in FILES:
    if not f.exists():
        continue

    src = f.read_text()

    if MARKER in src:
        continue

    block = """

    // PHASE10_CONTROLROOM_RUNTIME_MARKER

    private fun refreshRuntimeStatus() {

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
            VisionOverlayRegistry.enableAll()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
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
    }

"""

    if "override fun onResume()" in src:
        src = src.replace(
            "override fun onResume() {",
            "override fun onResume() {\n        refreshRuntimeStatus()",
            1
        )
        src = src.replace(
            "override fun onResume()",
            block + "\n    override fun onResume()",
            1
        )
    else:
        src += block

    f.write_text(src)

print("PATCH COMPLETE")
