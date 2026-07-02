#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg = Path.home() / "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

def replace(path, pattern, repl):
    p = pkg / path
    s = p.read_text()
    ns = re.sub(pattern, repl, s, flags=re.S)
    if ns != s:
        p.write_text(ns)

# FormationAdaptationEngine
replace(
    "FormationAdaptationEngine.kt",
    r'val confidence=\((.*?)\)\.coerceIn\(0f,1f\),\s*adaptationScore=confidence\.coerceIn\(0f,1f\),\s*formationStable=confidence>=0\.60f\s*\)',
    r'''val confidence=(\1).coerceIn(0f,1f)

        return FormationAdaptationResult(
            confidence=confidence,
            adaptationScore=confidence,
            formationStable=confidence>=0.60f
        )'''
)

# RuntimeConfidenceCalibrationEngine
replace(
    "RuntimeConfidenceCalibrationEngine.kt",
    r'val calibrated=\((.*?)\)\.coerceIn\(0f,1f\),\s*calibratedConfidence=calibrated\.coerceIn\(0f,1f\)\s*\)',
    r'''val calibrated=(\1).coerceIn(0f,1f)

        return RuntimeConfidenceCalibrationResult(
            calibratedConfidence=calibrated
        )'''
)

# OnlineParameterAdaptationEngine
replace(
    "OnlineParameterAdaptationEngine.kt",
    r'val gain=\((.*?)\)\.coerceIn\(0f,1f\),\s*adaptationGain=gain\.coerceIn\(0f,1f\)\s*\)',
    r'''val gain=(\1).coerceIn(0f,1f)

        return OnlineParameterAdaptationResult(
            adaptationGain=gain
        )'''
)

# Remove stray )
for f in [
    "OpponentBehaviourLearningEngine.kt",
    "PlayerTendencyLearningEngine.kt"
]:
    p = pkg / f
    s = p.read_text()
    s = re.sub(r'\n\)\n\s*return', '\n\n        return', s)
    p.write_text(s)

# PreferredPassingLaneLearningEngine duplicate laneScore
p = pkg / "PreferredPassingLaneLearningEngine.kt"
s = p.read_text()
s = re.sub(
    r'val laneScore=\s*\(\s*laneScore\*0\.70f\+\s*temporalScore\*0\.30f\s*\)',
    'val blendedLaneScore=(laneScore*0.70f+temporalScore*0.30f)',
    s,
    flags=re.S
)
s = s.replace("preferredLaneScore=laneScore","preferredLaneScore=blendedLaneScore")
s = s.replace("((laneScore+tactical.confidence)/2f)","((blendedLaneScore+tactical.confidence)/2f)")
p.write_text(s)

# ShootingHabitLearningEngine self-reference
p = pkg / "ShootingHabitLearningEngine.kt"
s = p.read_text()
s = re.sub(
    r'val confidence=\(\s*tactical\.confidence\*0\.40f\+\s*temporalConfidence\*0\.35f\+\s*confidence\*0\.25f',
    '''val laneConfidence=
            if(shooting.lanes.isEmpty()) 0f else shooting.lanes.maxOf{it.confidence}

        val confidence=(
            tactical.confidence*0.40f+
            temporalConfidence*0.35f+
            laneConfidence*0.25f''',
    s,
    flags=re.S
)
p.write_text(s)

# VisionCore temporal ordering
p = pkg / "VisionCore.kt"
s = p.read_text()

block = re.search(
r'val previousTemporalMemory =.*?TemporalMemoryEngine\.update\([\s\S]*?\n\s*\)',
s)

if block:
    txt = block.group(0)
    s = s.replace(txt,"")

anchor = re.search(
r'val tacticalBehaviorRecognitionResult =.*?\)\n',
s,
re.S)

if anchor:
    insert = '''

      val previousTemporalMemory =
          Phase3WorldStateStore.current().temporalMemoryState

      val temporalMemoryState =
          TemporalMemoryEngine.update(
              previousTemporalMemory,
              tacticalBehaviorRecognitionResult.confidence
          )

'''
    pos = anchor.end()
    s = s[:pos] + insert + s[pos:]

p.write_text(s)
PY

echo "========== VERIFY =========="

grep -n "return FormationAdaptationResult" \
"$PKG/FormationAdaptationEngine.kt"

grep -n "return RuntimeConfidenceCalibrationResult" \
"$PKG/RuntimeConfidenceCalibrationEngine.kt"

grep -n "return OnlineParameterAdaptationResult" \
"$PKG/OnlineParameterAdaptationEngine.kt"

grep -n "blendedLaneScore" \
"$PKG/PreferredPassingLaneLearningEngine.kt"

grep -n "laneConfidence" \
"$PKG/ShootingHabitLearningEngine.kt"

grep -n "val temporalMemoryState" \
"$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="

cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
