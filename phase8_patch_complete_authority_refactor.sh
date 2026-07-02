#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

files = {
    "controller": Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt"),
    "decision": Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt"),
    "comp": Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt"),
}

# ------------------------------------------------------------------
# ActiveGestureController
# ------------------------------------------------------------------

s = files["controller"].read_text()

s = re.sub(
r'''(?s)        val baseStrength =
.*?        val decision =''',
'''        val adaptiveDecisionAuthority =
            (
                decisionAuthority +
                shotAuthority +
                passAuthority +
                crossAuthority +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.onlineParameterAdaptationResult.adaptationGain +
                worldState.temporalMemoryState.temporalConfidence +
                worldState.tacticalAnalyticsResult.confidence +
                visionProximityConfidence
            ) / 9f

        val strength =
            (
                adaptiveDecisionAuthority
                    .coerceIn(0f,1f) * 100f
            ).toInt().coerceIn(0,100)

        val decision =''',
s,
count=1)

files["controller"].write_text(s)

# ------------------------------------------------------------------
# GameplayDecisionEngine
# ------------------------------------------------------------------

s = files["decision"].read_text()

s = re.sub(
r'''(?s)        val priority =
            \(
                strength \+
                \(telemetry\.playerVelocity \* 8f\)\.toInt\(\) \+
                \(telemetry\.confidence \* 20f\)\.toInt\(\)
            \)\.coerceIn\(0,100\)''',
'''        val priority =
            (
                (
                    visionAuthority * 0.55f +
                    decisionAuthority.coerceIn(0f,1f) * 0.30f +
                    adaptiveAuthority * 0.15f
                ).coerceIn(0f,1f) * 100f
            ).toInt().coerceIn(0,100)''',
s,
count=1)

files["decision"].write_text(s)

# ------------------------------------------------------------------
# HybridResponseCompensationEngine
# ------------------------------------------------------------------

s = files["comp"].read_text()

s = re.sub(
r'''(?s)        val responseBoost=
            \(strength\.coerceIn\(0,100\)/100f\)

        val worldState=''',
'''        val worldState=
''',
s,
count=1)

s = re.sub(
r'''(?s)        val adaptationGain=
            worldState\.onlineParameterAdaptationResult\.adaptationGain

        val predictiveFactor=
            \(
                temporal\.exponentialMovingAverage\+
                temporal\.rollingMean\+
                temporal\.temporalConfidence\+
                adaptiveConfidence\+
                adaptationGain\+
                responseBoost
            \)/6f''',
'''        val adaptationGain=
            worldState.onlineParameterAdaptationResult.adaptationGain

        val responseBoost =
            (
                adaptiveConfidence +
                adaptationGain +
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                (strength.coerceIn(0,100) / 100f)
            ) / 6f

        val predictiveFactor=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                adaptiveConfidence+
                adaptationGain+
                responseBoost
            )/6f''',
s,
count=1)

files["comp"].write_text(s)

print("PATCHED:", files["controller"])
print("PATCHED:", files["decision"])
print("PATCHED:", files["comp"])
PY

echo
echo "========== VERIFY =========="
grep -n "adaptiveDecisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val strength" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "val priority" -A8 adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "responseBoost" -A8 adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt

echo
echo "========== LEGACY VERIFY =========="
grep -n "passThreshold" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
grep -n "shotThreshold" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
grep -n "crossThreshold" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
grep -n "decisionScore" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
grep -n "playerVelocity \\* 8f" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt || true
grep -n "telemetry.confidence \\* 20f" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt || true

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
