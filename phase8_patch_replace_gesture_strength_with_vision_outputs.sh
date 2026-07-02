#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

old=r'''(?s)        val strength =
.*?\.coerceIn\(0,100\)'''

new='''        val gestureVisionAuthority =
            (
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.onlineParameterAdaptationResult.adaptationGain +
                worldState.temporalMemoryState.temporalConfidence +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.tacticalBehaviorRecognitionResult.confidence +
                worldState.tacticalIntelligenceResult.confidence +
                worldState.buildUpRecognitionResult.confidence +
                worldState.pressingRecognitionResult.confidence +
                worldState.counterPressRecognitionResult.confidence +
                worldState.possessionStyleRecognitionResult.confidence +
                worldState.defensiveCompactnessResult.confidence +
                worldState.wingOverloadDetectionResult.confidence +
                worldState.centralOverloadDetectionResult.confidence
            ) / 13f

        val gestureMotionAuthority =
            (
                telemetry.confidence +
                adaptiveConfidence +
                temporalGestureConfidence +
                visionProximityConfidence
            ) / 4f

        val strength =
            (
                (
                    gestureVisionAuthority * 0.80f +
                    gestureMotionAuthority * 0.20f
                ).coerceIn(0f,1f) * 100f
            ).toInt().coerceIn(0,100)'''

s2,n=re.subn(old,new,s,count=1)
if n!=1:
    raise SystemExit("FAILED locating gesture strength block")

p.write_text(s2)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "gestureVisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "gestureMotionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val strength =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
