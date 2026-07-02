#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

f = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
text = f.read_text()

patterns = [
r'''
[ \t]*val\ shotScore\s*=\s*
\s*shootingLaneScore\s*\+
\s*goalkeeperVisionBias\s*\+
\s*scene\.goalConfidence\s*
''',
r'''
[ \t]*val\ passScore\s*=\s*
\s*passingGraphScore\s*\+
\s*trajectory\.speed
\s*\.coerceAtMost\(12f\)\s*
''',
r'''
[ \t]*val\ crossScore\s*=\s*
\s*crossingLaneScore\s*\+
\s*scene\.fieldConfidence\s*
'''
]

for p in patterns:
    text,newcount = re.subn(p,"\n",text,flags=re.MULTILINE|re.VERBOSE)
    if newcount != 1:
        raise SystemExit(f"FAILED pattern:\n{p}")

for legacy in ("shotScore","passScore","crossScore"):
    if re.search(rf"\b{legacy}\b",text):
        raise SystemExit(f"{legacy} still exists")

f.write_text(text)
print("PATCHED:",f)
PY

echo
echo "========== VERIFY =========="
! grep -n '\<shotScore\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n '\<passScore\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n '\<crossScore\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
