#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

old=r'''(?s)        val mode =
            when \{
                visionProximityConfidence < 0\.35f -> 0
                hasBall && shotAuthority >= passAuthority && shotAuthority >= crossAuthority -> 2
                passAuthority >= crossAuthority -> 1
                else -> 0
            \}

        val baseStrength =
            \(
                \(
                    maxOf\(
                        passAuthority,
                        shotAuthority,
                        crossAuthority
                    \) \* 100f
                \)\.coerceIn\(0f,100f\)
            \)\.toInt\(\)

        val decisionScore =
            maxOf\(
                passAuthority,
                shotAuthority,
                crossAuthority
            \) \+ visionProximityConfidence'''

new='''        val decisionAuthority =
            (
                gestureVisionAuthority +
                passAuthority +
                shotAuthority +
                crossAuthority +
                visionProximityConfidence
            ) / 5f

        val mode =
            if (!hasBall) {
                0
            } else {
                listOf(
                    passAuthority,
                    shotAuthority,
                    crossAuthority
                ).withIndex()
                 .maxByOrNull { it.value }
                 ?.index ?: 0
            }

        val baseStrength =
            (
                decisionAuthority
                    .coerceIn(0f,1f) * 100f
            ).toInt()

        val decisionScore =
            decisionAuthority'''

s2,n=re.subn(old,new,s,count=1)
if n!=1:
    raise SystemExit("FAILED replacing decision pipeline")

s2=s2.replace(
'''                (baseStrength.coerceIn(0,100) / 100f) +

                (decisionScore.coerceIn(0f,100f) / 100f) +

                telemetryBoost.toFloat().coerceIn(0f,1f)

            ) / 7f''',
'''                decisionAuthority +

                telemetryBoost.toFloat().coerceIn(0f,1f)

            ) / 6f'''
)

p.write_text(s2)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "decisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val mode =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val baseStrength =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val decisionScore =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "gestureMotionAuthority" -A20 adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
