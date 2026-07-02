#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s = p.read_text()

# Remove the three legacy score declarations regardless of formatting.
for var in ("shotScore", "passScore", "crossScore"):
    pat = (
        rf'(?ms)^([ \t]*)val {var}\s*=\s*\n'
        rf'(?:\1[ \t]+.*\n)+'
    )
    s2, n = re.subn(pat, "", s, count=1)

    if n == 0:
        pat = (
            rf'(?ms)^([ \t]*)val {var}\s*=.*?\n'
            rf'(?=^[ \t]*val |^[ \t]*if\b|^[ \t]*when\b|^[ \t]*return\b|^[ \t]*GameplayDecisionEngine|^[ \t]*HybridResponseCompensationEngine|^\}}|\Z)'
        )
        s2, n = re.subn(pat, "", s, count=1)

    if n != 1:
        raise SystemExit(f"FAILED removing {var}")

    s = s2

for legacy in ("shotScore", "passScore", "crossScore"):
    if re.search(rf'\b{legacy}\b', s):
        raise SystemExit(f"{legacy} still referenced")

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
! grep -n "\bshotScore\b" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n "\bpassScore\b" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n "\bcrossScore\b" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
