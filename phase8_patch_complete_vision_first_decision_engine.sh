#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

controller = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
engine = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")

c = controller.read_text()
g = engine.read_text()

old_call = """        val decision =
            GameplayDecisionEngine.decide(
                mode = mode,
                strength = strength,
                shotScore = shotScore,
                passScore = passScore,
                crossScore = crossScore,
                telemetry = telemetry,
                worldState.temporalMemoryState)"""

new_call = """        val decision =
            GameplayDecisionEngine.decide(
                mode = mode,
                strength = strength,
                shotAuthority = shotAuthority,
                passAuthority = passAuthority,
                crossAuthority = crossAuthority,
                decisionAuthority = decisionAuthority,
                telemetry = telemetry,
                worldState.temporalMemoryState)"""

if old_call not in c:
    raise SystemExit("FAILED controller decide() call")

c = c.replace(old_call, new_call, 1)
controller.write_text(c)

g = g.replace(
"""        shotScore: Float,
        passScore: Float,
        crossScore: Float,
        telemetry: TelemetrySnapshot,
        temporal: TemporalMemoryState): DecisionResult {""",
"""        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        decisionAuthority: Float,
        telemetry: TelemetrySnapshot,
        temporal: TemporalMemoryState): DecisionResult {""",
1)

pattern = re.compile(
r'''(?s)        val normalizedShotAuthority =.*?        val confidence =
            \(
                visionAuthority \* 0\.80f \+
                adaptiveAuthority \* 0\.20f
            \)\.coerceIn\(0f,1f\)'''
)

replacement = '''        val normalizedShotAuthority =
            shotAuthority.coerceIn(0f,1f)

        val normalizedPassAuthority =
            passAuthority.coerceIn(0f,1f)

        val normalizedCrossAuthority =
            crossAuthority.coerceIn(0f,1f)

        val adaptiveAuthority =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f)
            ) / 6f

        val visionAuthority =
            when (mode) {
                2 -> normalizedShotAuthority
                1 -> normalizedPassAuthority
                else -> normalizedCrossAuthority
            }

        val confidence =
            (
                visionAuthority * 0.60f +
                decisionAuthority.coerceIn(0f,1f) * 0.25f +
                adaptiveAuthority * 0.15f
            ).coerceIn(0f,1f)'''

g, n = pattern.subn(replacement, g, count=1)
if n != 1:
    raise SystemExit("FAILED GameplayDecisionEngine authority block")

engine.write_text(g)

print("PATCHED:", controller)
print("PATCHED:", engine)
PY

echo
echo "========== VERIFY =========="
grep -n "shotAuthority =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "GameplayDecisionEngine.decide" -A8 adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "decisionAuthority: Float" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "visionAuthority" -A8 adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "decisionAuthority.coerceIn" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
