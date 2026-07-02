#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path.home() / "projects" / "Splendor-Assist"
target = root / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt"

if not target.exists():
    print("PATCH ABORTED")
    print(target)
    sys.exit(1)

text = target.read_text()

imports = """
import com.assistant.adapter.smartassist.fps.FrameDropStabilizer
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import com.assistant.adapter.smartassist.fps.MemoryStabilityOptimizer
import com.assistant.adapter.smartassist.fps.VsyncInputAnchor
"""

if "import com.assistant.adapter.smartassist.fps.MemoryStabilityOptimizer" not in text:
    pkg = re.search(r"^package[^\n]*\n", text, re.M)
    if pkg:
        text = text[:pkg.end()] + imports + text[pkg.end():]

replacements = {
    r"runCatching\s*\{\s*MemoryStabilityOptimizer\s*\}":
        "runCatching { /* MemoryStabilityOptimizer requires Context */ }",
    r"runCatching\s*\{\s*FrameDropStabilizer\s*\}":
        "runCatching { FrameDropStabilizer() }",
    r"runCatching\s*\{\s*VsyncInputAnchor\s*\}":
        "runCatching { /* VsyncInputAnchor requires constructor parameters */ }",
    r"runCatching\s*\{\s*LatencyDefeatingInputEngine\s*\}":
        "runCatching { /* LatencyDefeatingInputEngine requires constructor parameters */ }",
}

for pattern, repl in replacements.items():
    text = re.sub(pattern, repl, text)

target.write_text(text)
print("PATCH COMPLETE")
print(target)
