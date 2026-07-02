#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

patterns = [

r'''(?ms)^(\s*)val shotScore =
(?:\1    .*\n)+''',

r'''(?ms)^(\s*)val passScore =
(?:\1    .*\n)+''',

r'''(?ms)^(\s*)val crossScore =
(?:\1    .*\n)+'''
]

for pat in patterns:
    s,n = re.subn(pat,"",s,count=1)
    if n!=1:
        raise SystemExit(f"FAILED removing block:\n{pat}")

# Verify no remaining references except historical names inside authority variables
for name in ("shotScore","passScore","crossScore"):
    leftovers=[
        line for line in s.splitlines()
        if name in line
    ]
    if leftovers:
        raise SystemExit(f"{name} still referenced:\n"+"\n".join(leftovers))

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "shotAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "passAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "crossAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== VERIFY LEGACY =========="
! grep -n "shotScore" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n "passScore" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
! grep -n "crossScore" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
