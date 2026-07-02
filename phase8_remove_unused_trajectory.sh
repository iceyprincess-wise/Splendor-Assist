#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s = p.read_text()

m = re.search(
    r'(?ms)^([ \t]*)val trajectory\s*=\s*\n(?:\1[ \t]+.*\n)+',
    s
)

if not m:
    raise SystemExit("FAILED locating trajectory declaration")

block = m.group(0)

if ".speed" in s[m.end():] or ".angle" in s[m.end():] or ".duration" in s[m.end():]:
    raise SystemExit("trajectory is still referenced somewhere; aborting")

s = s[:m.start()] + s[m.end():]

p.write_text(s)
print("PATCHED:", p)
PY

echo
echo "========== VERIFY =========="
! grep -n '\<trajectory\>' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
