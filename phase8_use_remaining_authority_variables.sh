#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

f=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
src=f.read_text()

old="""        val authorityStability =
            (
                inputDiagnostics.stabilityScore +
                defenseAuthority.pressure +
                defenseAuthority.containment +
                defenseAuthority.interception +
                visionAuthority
            ) * 10f"""

new="""        val authorityStability =
            (
                inputDiagnostics.stabilityScore +
                defenseAuthority.pressure +
                defenseAuthority.containment +
                defenseAuthority.interception +
                lowBlockAuthority +
                wingBlockAuthority +
                shieldAuthority +
                visionAuthority
            ) * 10f"""

src,n=re.subn(re.escape(old),new,src)
if n!=1:
    raise SystemExit("authorityStability block not found")

f.write_text(src)
print("PATCHED:",f)
PY

echo
echo "========== VERIFY =========="
grep -n -A10 "val authorityStability" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "lowBlockAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "wingBlockAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "shieldAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
