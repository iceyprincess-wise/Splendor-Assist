#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/ "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# ------------------------------------------------------------------
# GameplayDecisionEngine
# ------------------------------------------------------------------

g=pkg/"GameplayDecisionEngine.kt"
if g.exists():
    s=g.read_text()

    if "temporalMemoryState: TemporalMemoryState" not in s:
        s=re.sub(
            r'(worldState:\s*Phase3WorldState)',
            r'\1,\n        temporalMemoryState: TemporalMemoryState',
            s,
            count=1,
            flags=re.S
        )

    if "val temporalDecisionConfidence" not in s:
        block="""

        val temporalDecisionConfidence =
            (
                temporalMemoryState.temporalConfidence * 0.30f +
                temporalMemoryState.exponentialMovingAverage * 0.20f +
                temporalMemoryState.rollingMean * 0.15f +
                (0.5f + temporalMemoryState.confidenceTrend * 0.5f).coerceIn(0f,1f) * 0.15f +
                (1f - temporalMemoryState.confidenceVariance).coerceIn(0f,1f) * 0.10f +
                temporalMemoryState.historyStability * 0.05f +
                temporalMemoryState.decayFactor * 0.05f
            ).coerceIn(0f,1f)

"""
        s=s.replace("return GameplayDecisionResult(",block+"\n        return GameplayDecisionResult(",1)

    s=re.sub(
        r'confidence\s*=\s*([A-Za-z0-9_\.]+)',
        r'confidence=((\1)+temporalDecisionConfidence)/2f',
        s,
        count=1
    )

    g.write_text(s)

# ------------------------------------------------------------------
# ActiveGestureController
# ------------------------------------------------------------------

a=pkg/"ActiveGestureController.kt"
if a.exists():
    s=a.read_text()

    if "temporal = worldState.temporalMemoryState" not in s:
        anchor="val adaptiveConfidence = worldState.runtimeConfidenceCalibrationResult.calibratedConfidence"
        insert="""
        val temporal = worldState.temporalMemoryState

        val temporalGestureConfidence =
            (
                temporal.temporalConfidence * 0.30f +
                temporal.exponentialMovingAverage * 0.20f +
                temporal.rollingMean * 0.15f +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f) * 0.15f +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) * 0.10f +
                temporal.historyStability * 0.05f +
                temporal.decayFactor * 0.05f
            ).coerceIn(0f,1f)

"""
        s=s.replace(anchor,anchor+"\n"+insert,1)

    s=s.replace(
        "(adaptiveConfidence * 8f)",
        "((adaptiveConfidence + temporalGestureConfidence) * 8f)"
    )

    a.write_text(s)

# ------------------------------------------------------------------
# VisionCore call
# ------------------------------------------------------------------

v=pkg/"VisionCore.kt"
if v.exists():
    s=v.read_text()

    s=s.replace(
"""GameplayDecisionEngine.decide(
                worldState
        )""",
"""GameplayDecisionEngine.decide(
                worldState,
                temporalMemoryState
        )"""
    )

    v.write_text(s)

PY

echo "========== VERIFY =========="

grep -n "temporalDecisionConfidence" \
"$PKG/GameplayDecisionEngine.kt" || true

grep -n "temporalGestureConfidence" \
"$PKG/ActiveGestureController.kt" || true

grep -n "GameplayDecisionEngine.decide" \
"$PKG/VisionCore.kt" || true

echo
echo "========== BUILD =========="

cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
