#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$HOME/projects/Splendor-Assist"

python3 <<'PY'
from pathlib import Path

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt")
s=p.read_text()

old="""        val responseBoost=
            (strength.coerceIn(0,100)/100f)

        val predictiveFactor=
            0.90f + (responseBoost*2.80f)

        val compensatedX=
            endX + dx*predictiveFactor

        val compensatedY=
            endY + dy*predictiveFactor

        val reducedDuration=
            (duration*(1f-(responseBoost*0.72f)))
                .toLong()
                .coerceAtLeast(8L)

        val confidence=
            (0.82f + responseBoost*0.18f)
                .coerceAtMost(1f)

        val urgency=
            (distance/8f)
                .toInt()
                .coerceAtMost(100)
"""

new="""        val responseBoost=
            (strength.coerceIn(0,100)/100f)

        val worldState=
            Phase3WorldStateStore.current()

        val temporal=
            worldState.temporalMemoryState

        val adaptiveConfidence=
            worldState.runtimeConfidenceCalibrationResult.calibratedConfidence

        val adaptationGain=
            worldState.onlineParameterAdaptationResult.adaptationGain

        val predictiveFactor=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                adaptiveConfidence+
                adaptationGain+
                responseBoost
            )/6f

        val compensatedX=
            endX + dx*predictiveFactor

        val compensatedY=
            endY + dy*predictiveFactor

        val durationScale=
            (1f-(predictiveFactor*temporal.temporalConfidence))
                .coerceIn(0.15f,1f)

        val reducedDuration=
            (duration*durationScale)
                .toLong()
                .coerceAtLeast(8L)

        val confidence=
            (
                adaptiveConfidence+
                temporal.temporalConfidence+
                predictiveFactor
            )/3f

        val urgency=
            (
                (distance/8f)*
                (1f+temporal.confidenceTrend)*
                (1f+adaptationGain)
            )
                .toInt()
                .coerceIn(0,100)
"""

if old not in s:
    raise SystemExit("Expected block not found.")

p.write_text(s.replace(old,new,1))
PY

echo "========== VERIFY =========="
grep -n "Phase3WorldStateStore.current" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
grep -n "temporalMemoryState" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
grep -n "runtimeConfidenceCalibrationResult" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
grep -n "onlineParameterAdaptationResult" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
grep -n "predictiveFactor" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
