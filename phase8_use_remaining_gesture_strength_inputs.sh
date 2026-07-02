#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

replacements = [
(
r'''(?m)^(\s*)val gestureMotionAuthority =''',
r'''\1val gestureMotionAuthority =
\1    (
\1        telemetry.confidence +
\1        adaptiveConfidence +
\1        temporalGestureConfidence +
\1        visionProximityConfidence +
\1        (baseStrength.coerceIn(0,100) / 100f) +
\1        (decisionScore.coerceIn(0f,100f) / 100f) +
\1        telemetryBoost.coerceIn(0f,1f)
\1    ) / 7f

\1// original'''
)
]

for pat, rep in replacements:
    s = re.sub(pat, rep, s, count=1)

s = s.replace("// original\n        val gestureMotionAuthority =", "")

p.write_text(s)
print("PATCHED:", p)
PY

echo
echo "========== VERIFY =========="
grep -n "baseStrength" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "decisionScore" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "telemetryBoost" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "gestureMotionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
