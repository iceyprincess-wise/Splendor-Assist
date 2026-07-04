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

IMPORTS = [
"import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator",
"import com.assistant.adapter.smartassist.RuntimeDiagnosticsRegistry",
"import com.assistant.adapter.smartassist.RuntimeVisualizationRegistry",
"import com.assistant.adapter.smartassist.RuntimeOverlayHub",
"import com.assistant.adapter.smartassist.VisionOverlayRegistry",
"import com.assistant.adapter.smartassist.FPSMonitor",
"import com.assistant.adapter.smartassist.VisionLatencyMonitor",
"import com.assistant.adapter.smartassist.ConfidenceHeatmap",
]

for f in FILES:

    if not f.exists():
        continue

    txt = f.read_text()

    if "package " not in txt:
        continue

    lines = txt.splitlines()

    insert = 0
    for i,l in enumerate(lines):
        if l.startswith("import "):
            insert = i + 1

    existing = set(lines)

    for imp in reversed(IMPORTS):
        if imp not in existing:
            lines.insert(insert, imp)

    txt = "\n".join(lines)
    f.write_text(txt)

print("PATCH COMPLETE")
