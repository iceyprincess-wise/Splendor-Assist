#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s = p.read_text()

# These references remained after the migration because only the declarations
# were replaced. The consumers must now use the authority variables.

subs = [
    (r'\bshotScore\b',  'shotAuthority'),
    (r'\bpassScore\b',  'passAuthority'),
    (r'\bcrossScore\b', 'crossAuthority'),
]

for old,new in subs:
    s = re.sub(old,new,s)

# Ensure no legacy score identifiers remain.
for legacy in ("shotScore","passScore","crossScore"):
    if re.search(rf'\b{legacy}\b', s):
        raise SystemExit(f"FAILED: {legacy} still present")

p.write_text(s)
print("PATCHED:", p)
PY

echo
echo "========== VERIFY =========="
grep -n "shotAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "passAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "crossAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== VERIFY LEGACY =========="
! grep -n '\<shotScore\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n '\<passScore\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n '\<crossScore\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
