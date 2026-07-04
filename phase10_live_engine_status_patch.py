from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

FILES = [
    ROOT/"app/src/main/java/com/assistant/MainActivity.kt",
    ROOT/"app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt",
    ROOT/"app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt",
    ROOT/"app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt",
    ROOT/"app/src/main/java/com/assistant/controlroom/ui/FutureRoomsActivity.kt",
    ROOT/"app/src/main/java/com/assistant/overlay/ui/AnalyticsTheaterActivity.kt",
]

MARKER = "PHASE10_ENGINE_STATUS_REFRESH_MARKER"

SNIPPET = """

    // PHASE10_ENGINE_STATUS_REFRESH_MARKER
    private fun refreshEngineStatus() {

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        }

        runCatching {
            RuntimeVisualizationRegistry.enableVisualization()
        }

        runCatching {
            VisionOverlayRegistry.enableAll()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
        }
    }

"""

for f in FILES:
    if not f.exists():
        continue

    txt = f.read_text()

    if MARKER in txt:
        continue

    m = re.search(r'private fun refreshRuntimeStatus\(\)', txt)

    if m:
        txt = txt.replace(
            "private fun refreshRuntimeStatus()",
            SNIPPET + "\n    private fun refreshRuntimeStatus()",
            1
        )

        txt = txt.replace(
            "refreshRuntimeStatus()",
            "refreshRuntimeStatus()\n        refreshEngineStatus()",
            1
        )

    f.write_text(txt)

print("PATCH COMPLETE")
