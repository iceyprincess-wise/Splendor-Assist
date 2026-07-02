from pathlib import Path
import re
import sys

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt"

text = FILE.read_text(encoding="utf-8")

pattern = re.compile(
    r'data class VisionOverlayRegistryState\(\s*'
    r'val vision = VisionConfigurationEngine\.current\(\),\s*'
    r'val tracking = TrackingConfigurationEngine\.current\(\),\s*'
    r'val overlay = VisionDebugOverlay\.current\(\)\s*'
    r'\)',
    re.S
)

replacement = """data class VisionOverlayRegistryState(
    val vision: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val tracking: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val overlay: VisionDebugOverlayState =
        VisionDebugOverlay.current()
)"""

text, count = pattern.subn(replacement, text, count=1)

if count != 1:
    print("PATCH FAILED")
    sys.exit(2)

FILE.write_text(text, encoding="utf-8")
print("PATCH COMPLETE")
