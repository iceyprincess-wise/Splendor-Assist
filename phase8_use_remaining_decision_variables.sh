#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

old=r'''(?s)        val strength =
            \(
                \(
                    gestureVisionAuthority \* 0\.80f \+
                    gestureMotionAuthority \* 0\.20f
                \)\.coerceIn\(0f,1f\) \* 100f
            \)\.toInt\(\)\.coerceIn\(0,100\)'''

new='''        val strength =
            (
                (
                    gestureVisionAuthority * 0.65f +
                    gestureMotionAuthority * 0.20f +
                    (baseStrength.coerceIn(0,100).toFloat() / 100f) * 0.10f +
                    decisionScore.coerceIn(0f,1f) * 0.05f
                ).coerceIn(0f,1f) * 100f
            ).toInt().coerceIn(0,100)'''

s,n=re.subn(old,new,s,count=1)
if n!=1:
    raise SystemExit("FAILED locating strength block")

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -nA12 "val strength =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "baseStrength" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "decisionScore" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
