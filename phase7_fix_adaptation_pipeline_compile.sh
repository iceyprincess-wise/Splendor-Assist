#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# -------------------------------------------------------
# ActiveGestureController
# -------------------------------------------------------

f=pkg/"ActiveGestureController.kt"
s=f.read_text()

# Remove misplaced adaptiveConfidence insertion if it split a declaration.
s=re.sub(
    r'(\n\s*val\s+decisionScore\s*=.*?)\n\s*val\s+adaptiveConfidence\s*=.*?\n',
    r'\1\n',
    s,
    flags=re.S
)

# Reinsert after decisionScore block ends (before telemetryBoost).
m=re.search(
    r'(val\s+decisionScore\s*=.*?\n)(\s*val\s+telemetryBoost\s*=)',
    s,
    re.S
)
if m and "adaptiveConfidence" not in m.group(1):
    s=s[:m.start()] + \
      m.group(1) + \
      "        val adaptiveConfidence = worldState.runtimeConfidenceCalibrationResult.calibratedConfidence\n\n" + \
      m.group(2) + \
      s[m.end():]

# Ensure adaptiveConfidence contributes.
s=s.replace(
    "(visionProximityConfidence * 12f) + (worldState.onlineParameterAdaptationResult.adaptationGain * 10f)",
    "((visionProximityConfidence * 12f) + "
    "(worldState.onlineParameterAdaptationResult.adaptationGain * 10f) + "
    "(adaptiveConfidence * 8f))"
)

f.write_text(s)

# -------------------------------------------------------
# VisionCore
# -------------------------------------------------------

v=pkg/"VisionCore.kt"
t=v.read_text()

# Remove trailing comma before Phase3WorldState closing parenthesis.
t=re.sub(r',(\s*\))', r'\1', t)

v.write_text(t)
PY

echo
echo "========== VERIFY =========="
grep -n "adaptiveConfidence" "$PKG/ActiveGestureController.kt"
grep -n "onlineParameterAdaptationResult" "$PKG/ActiveGestureController.kt"
grep -n "formationAdaptationResult =" "$PKG/VisionCore.kt"
grep -n "runtimeConfidenceCalibrationResult =" "$PKG/VisionCore.kt"
grep -n "onlineParameterAdaptationResult =" "$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
