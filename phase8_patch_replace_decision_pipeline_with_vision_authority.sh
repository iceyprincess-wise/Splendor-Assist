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

                visionProximityConfidence < 0\.35f ->
                    0

                hasBall &&
                shotScore >= passScore &&
                shotScore >= crossScore ->
                    2

                passScore >= crossScore ->
                    1

                else ->
                    0
            }

        val baseStrength =
            when \(mode\) \{
                1 -> SmartAssistRepository\.configuration\(\)\.passThreshold
                2 -> SmartAssistRepository\.configuration\(\)\.shotThreshold
                else -> SmartAssistRepository\.configuration\(\)\.crossThreshold
            }

        val decisionScore =
            \(
                when \(mode\) \{
                    2 -> shotScore
                    1 -> passScore
                    else -> crossScore
                \}
            \) \+ visionProximityConfidence'''

new='''        val passAuthority =
            (
                passingGraphScore +
                worldState.buildUpRecognitionResult.confidence +
                worldState.possessionStyleRecognitionResult.confidence +
                worldState.tacticalBehaviorRecognitionResult.confidence
            ) / 4f

        val shotAuthority =
            (
                shootingLaneScore +
                worldState.tacticalIntelligenceResult.confidence +
                worldState.pressingRecognitionResult.confidence +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
            ) / 4f

        val crossAuthority =
            (
                crossingLaneScore +
                worldState.wingOverloadDetectionResult.confidence +
                worldState.centralOverloadDetectionResult.confidence +
                worldState.onlineParameterAdaptationResult.adaptationGain
            ) / 4f

        val mode =
            when {
                visionProximityConfidence < 0.35f -> 0
                hasBall && shotAuthority >= passAuthority && shotAuthority >= crossAuthority -> 2
                passAuthority >= crossAuthority -> 1
                else -> 0
            }

        val baseStrength =
            (
                (
                    maxOf(
                        passAuthority,
                        shotAuthority,
                        crossAuthority
                    ) * 100f
                ).coerceIn(0f,100f)
            ).toInt()

        val decisionScore =
            maxOf(
                passAuthority,
                shotAuthority,
                crossAuthority
            ) + visionProximityConfidence'''

s2,n=re.subn(old,new,s,count=1)
if n!=1:
    raise SystemExit("FAILED replacing decision pipeline")

p.write_text(s2)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "passAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "shotAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "crossAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val mode =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val baseStrength =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val decisionScore =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
