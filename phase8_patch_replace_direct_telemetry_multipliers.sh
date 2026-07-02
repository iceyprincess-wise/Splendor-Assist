#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

# telemetryBoost
s,n=re.subn(
r'''(?s)        val telemetryBoost =
.*?(?=\n\s*val gestureVisionAuthority =)''',
'''        val telemetryAuthority =
            (
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.onlineParameterAdaptationResult.adaptationGain +
                worldState.temporalMemoryState.temporalConfidence +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.tacticalIntelligenceResult.confidence +
                telemetry.confidence
            ) / 6f

''',
s,
count=1)
if n!=1:
    raise SystemExit("FAILED telemetryBoost")

# gestureMotionAuthority
s,n=re.subn(
r'''(?s)        val gestureMotionAuthority =
            \(
.*?\n            \) / 7f''',
'''        val gestureMotionAuthority =
            (
                baseStrength.coerceIn(0,100).toFloat()/100f +
                decisionScore.coerceIn(0f,100f)/100f +
                adaptiveConfidence +
                temporalGestureConfidence +
                visionProximityConfidence +
                telemetryAuthority +
                worldState.onlineParameterAdaptationResult.adaptationGain
            ) / 7f''',
s,
count=1)
if n!=1:
    raise SystemExit("FAILED gestureMotionAuthority")

p.write_text(s)
print("PATCHED:",p)
PY

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")
s=p.read_text()

old=r'''\(telemetry\.playerVelocity \* 8f\)\.toInt\(\)\s*\+\s*
\s*\(telemetry\.confidence \* 20f\)\.toInt\(\)'''

new='''
(
(
runtimeConfidenceCalibrationResult.calibratedConfidence +
onlineParameterAdaptationResult.adaptationGain +
temporalMemoryState.temporalConfidence +
telemetry.confidence
) * 25f
).toInt()
'''

s,n=re.subn(old,new,s,count=1)
if n!=1:
    raise SystemExit("FAILED GameplayDecisionEngine")

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "telemetryAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "gestureMotionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "runtimeConfidenceCalibrationResult.calibratedConfidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
