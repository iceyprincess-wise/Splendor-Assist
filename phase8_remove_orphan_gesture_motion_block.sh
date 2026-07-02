#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

pattern=r'''
[ \t]*//\s*original\s*
[ \t]*\(
\s*telemetry\.confidence\s*\+
\s*adaptiveConfidence\s*\+
\s*temporalGestureConfidence\s*\+
\s*visionProximityConfidence
\s*\)\s*/\s*4f
'''

s2,n=re.subn(pattern,'',s,flags=re.MULTILINE|re.VERBOSE)

if n!=1:
    raise SystemExit(f"Expected to remove exactly one orphan block, removed {n}")

p.write_text(s2)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "// original" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
grep -A12 -n "val gestureMotionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -A6 -n "val strength =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
