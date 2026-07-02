#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
FILE="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt"

python3 <<'PY'
from pathlib import Path
import re

f=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt"
s=f.read_text(encoding="utf-8")

# ---------------------------------------------------------
# decisionDistance must contribute to the decision pipeline.
# ---------------------------------------------------------

if "decisionDistance * 0.01f" not in s:

    s=re.sub(
        r'(\(\(visionProximityConfidence\s*\*\s*12f\)\s*\+\s*\(worldState\.onlineParameterAdaptationResult\.adaptationGain\s*\*\s*10f\)\s*\+\s*\(adaptiveConfidence\s*\*\s*8f\)\))',
        r'(\1 + (decisionDistance * 0.01f))',
        s,
        count=1,
        flags=re.S
    )

f.write_text(s,encoding="utf-8")
PY

echo
echo "========== VERIFY =========="
grep -n "decisionDistance" "$FILE"
grep -n "decisionDistance \\* 0.01f" "$FILE"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
