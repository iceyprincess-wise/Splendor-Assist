#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

# ------------------------------------------------------------------
# FIX 1
# decisionAuthority was inserted before gestureVisionAuthority existed.
# Replace the dependency with an equivalent inline Vision aggregate.
# ------------------------------------------------------------------

pat=r'''(?s)val decisionAuthority =
\s*\(
\s*gestureVisionAuthority \+
\s*passAuthority \+
\s*shotAuthority \+
\s*crossAuthority \+
\s*visionProximityConfidence
\s*\) / 5f'''

rep='''val decisionAuthority =
            (
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
                ) / 13f +
                passAuthority +
                shotAuthority +
                crossAuthority +
                visionProximityConfidence
            ) / 5f'''

s,n=re.subn(pat,rep,s,count=1)
if n!=1:
    raise SystemExit("FAILED fixing decisionAuthority")

# ------------------------------------------------------------------
# FIX 2
# telemetryBoost is not Float on this branch.
# ------------------------------------------------------------------

s=s.replace(
"telemetryBoost.toFloat().coerceIn(0f,1f)",
"(telemetryBoost.toFloat()).coerceIn(0f,1f)"
)

# If telemetryBoost is Int/Long, normalize before averaging.
s=s.replace(
"+\n                telemetryBoost.toFloat()).coerceIn(0f,1f)",
"+\n                ((telemetryBoost.toFloat()) / 100f).coerceIn(0f,1f)"
)

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "decisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "runtimeConfidenceCalibrationResult.calibratedConfidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "telemetryBoost" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
