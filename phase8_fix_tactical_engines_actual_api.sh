#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

ROOT=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

def patch(name, old, new):
    p=ROOT/name
    s=p.read_text()
    s,n=re.subn(old,new,s,flags=re.S)
    if n!=1:
        raise SystemExit(f"Failed to patch {name}")
    p.write_text(s)

patch(
"TacticalMapGenerationEngine.kt",
r'''TacticalMapResult\([\s\S]*?\n\s*\)''',
'''TacticalMapResult(
            width=occupancy.columns,
            height=occupancy.rows,
            cells=FloatArray(occupancy.columns*occupancy.rows),
            confidence=scene.confidence.coerceIn(0f,1f)
        )'''
)

patch(
"DefensiveCompactnessEngine.kt",
r'''DefensiveCompactnessResult\([\s\S]*?\n\s*\)''',
'''DefensiveCompactnessResult(
            horizontalCompactness=teamShape.width.coerceIn(0f,1f),
            verticalCompactness=teamShape.depth.coerceIn(0f,1f),
            compactness=teamShape.compactness.coerceIn(0f,1f),
            confidence=((teamShape.confidence+defensiveLine.confidence)/2f).coerceIn(0f,1f)
        )'''
)

patch(
"WingOverloadDetectionEngine.kt",
r'''WingOverloadDetectionResult\([\s\S]*?\n\s*\)''',
'''WingOverloadDetectionResult(
            leftWingAdvantage=0.5f,
            rightWingAdvantage=0.5f,
            overloaded=false,
            confidence=scene.confidence.coerceIn(0f,1f)
        )'''
)

patch(
"CentralOverloadDetectionEngine.kt",
r'''CentralOverloadDetectionResult\([\s\S]*?\n\s*\)''',
'''CentralOverloadDetectionResult(
            centralControl=scene.fieldConfidence.coerceIn(0f,1f),
            overloaded=scene.playerCount>=8,
            confidence=scene.confidence.coerceIn(0f,1f)
        )'''
)

patch(
"PressingRecognitionEngine.kt",
r'''PressingRecognitionResult\([\s\S]*?\n\s*\)''',
'''PressingRecognitionResult(
            detected=formation.found && compactness.compactness>0.55f,
            confidence=((formation.confidence+compactness.confidence)/2f).coerceIn(0f,1f)
        )'''
)

patch(
"CounterPressRecognitionEngine.kt",
r'''CounterPressRecognitionResult\([\s\S]*?\n\s*\)''',
'''CounterPressRecognitionResult(
            detected=possession.hasPossession && possession.possessionChanged,
            confidence=((scene.confidence+possession.confidence)/2f).coerceIn(0f,1f)
        )'''
)

patch(
"BuildUpRecognitionEngine.kt",
r'''BuildUpRecognitionResult\([\s\S]*?\n\s*\)''',
'''BuildUpRecognitionResult(
            detected=formation.found && graph.lanes.isNotEmpty(),
            confidence=((formation.confidence+teamShape.confidence)/2f).coerceIn(0f,1f)
        )'''
)

patch(
"PossessionStyleRecognitionEngine.kt",
r'''PossessionStyleRecognitionResult\([\s\S]*?\n\s*\)''',
'''PossessionStyleRecognitionResult(
            detected=possession.hasPossession && possession.possessionFrames>30,
            confidence=((possession.confidence+scene.fieldConfidence)/2f).coerceIn(0f,1f)
        )'''
)

print("Patched.")
PY

echo "========== VERIFY =========="
grep -RIn \
'width=occupancy.columns\|horizontalCompactness=teamShape.width\|centralControl=scene.fieldConfidence\|formation.found\|hasPossession\|possessionFrames' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
