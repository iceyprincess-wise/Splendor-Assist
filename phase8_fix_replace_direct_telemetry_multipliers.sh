#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s=p.read_text()

start=s.index("        val gestureMotionAuthority =")
end=s.index("        val strength =", start)

replacement="""        val gestureMotionAuthority =
            (
                baseStrength.coerceIn(0,100).toFloat() / 100f +
                decisionScore.coerceIn(0f,100f) / 100f +
                adaptiveConfidence +
                temporalGestureConfidence +
                visionProximityConfidence +
                telemetryAuthority +
                worldState.onlineParameterAdaptationResult.adaptationGain
            ) / 7f

"""

s=s[:start]+replacement+s[end:]

start=s.index("        val telemetryAuthority =")
end=s.index("        val gestureVisionAuthority =", start)

replacement="""        val telemetryAuthority =
            (
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.onlineParameterAdaptationResult.adaptationGain +
                worldState.temporalMemoryState.temporalConfidence +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.tacticalIntelligenceResult.confidence +
                telemetry.confidence
            ) / 6f

"""

s=s[:start]+replacement+s[end:]

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -A12 -n "val telemetryAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -A12 -n "val gestureMotionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
