#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

old="""                telemetryBoost.coerceIn(0f,1f)"""

new="""                telemetryBoost.toFloat().coerceIn(0f,1f)"""

if old not in s:
    raise SystemExit("FAILED: telemetryBoost expression not found")

s=s.replace(old,new,1)

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "telemetryBoost" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
