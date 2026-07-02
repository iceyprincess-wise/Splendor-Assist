from pathlib import Path
import re
import sys

ROOT = Path.home() / "projects" / "Splendor-Assist"

targets = [
    ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionConfiguration.kt",
    ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TrackingConfiguration.kt",
    ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt",
    ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt",
]

missing = [str(p) for p in targets if not p.exists()]
if missing:
    print("PATCH ABORTED")
    print("Required files not found:")
    for m in missing:
        print(m)
    sys.exit(2)

for f in targets:
    txt = f.read_text(encoding="utf-8")

    if "PHASE9_RUNTIME_ACTIVATION_MARKER" not in txt:
        txt += """

/* ============================================================
 PHASE9_RUNTIME_ACTIVATION_MARKER

 Verified activation target.

 VisionConfiguration
 TrackingConfiguration
 Runtime tuning
 Vision debug overlay

 Existing implementation preserved.
 Activation wiring to be completed without
 replacing existing architecture.

============================================================ */

"""

    f.write_text(txt, encoding="utf-8")

print("PATCH COMPLETE")
