#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

# Locate gesture authority calculation.
m=re.search(
r'val decisionAuthority\s*=\s*\(\s*(.*?)\s*\)\s*/\s*5f',
s,
re.S
)

if not m:
    raise SystemExit("FAILED locating decisionAuthority")

old=m.group(0)

new="""val decisionAuthority =
            (
                shotAuthority +
                passAuthority +
                crossAuthority +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.temporalMemoryState.temporalConfidence +
                (decisionDistance / 2500f).coerceIn(0f,1f)
            ) / 6f"""

s=s.replace(old,new,1)

if "decisionDistance" not in new:
    raise SystemExit("decisionDistance not connected")

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "decisionDistance" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -nA10 "val decisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
