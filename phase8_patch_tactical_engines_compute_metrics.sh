#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

ROOT=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

PATCHES={

"TacticalMapGenerationEngine.kt":(
r'''TacticalMapResult\(\)''',
'''TacticalMapResult(
            width=occupancy.width,
            height=occupancy.height,
            cells=pressure.field,
            confidence=(
                occupancy.cells.size.toFloat()/
                (occupancy.width*occupancy.height).coerceAtLeast(1).toFloat()
            ).coerceIn(0f,1f)
        )'''
),

"DefensiveCompactnessEngine.kt":(
r'''DefensiveCompactnessResult\(\)''',
'''DefensiveCompactnessResult(
            horizontalCompactness=
                (1f-defensiveLine.spread).coerceIn(0f,1f),
            verticalCompactness=
                (1f-teamShape.depth).coerceIn(0f,1f),
            compactness=
                (
                    ((1f-defensiveLine.spread).coerceIn(0f,1f))+
                    ((1f-teamShape.depth).coerceIn(0f,1f))
                )/2f,
            confidence=scene.fieldConfidence.coerceIn(0f,1f)
        )'''
),

"WingOverloadDetectionEngine.kt":(
r'''WingOverloadDetectionResult\(\)''',
'''WingOverloadDetectionResult(
            leftWingAdvantage=occupancy.leftDensity.coerceIn(0f,1f),
            rightWingAdvantage=occupancy.rightDensity.coerceIn(0f,1f),
            overloaded=
                kotlin.math.abs(
                    occupancy.leftDensity-
                    occupancy.rightDensity
                )>0.20f,
            confidence=pressure.confidence.coerceIn(0f,1f)
        )'''
),

"CentralOverloadDetectionEngine.kt":(
r'''CentralOverloadDetectionResult\(\)''',
'''CentralOverloadDetectionResult(
            centralControl=occupancy.centralDensity.coerceIn(0f,1f),
            overloaded=occupancy.centralDensity>0.60f,
            confidence=pressure.confidence.coerceIn(0f,1f)
        )'''
),

"PressingRecognitionEngine.kt":(
r'''PressingRecognitionResult\(\)''',
'''PressingRecognitionResult(
            detected=
                pressure.confidence>0.60f &&
                compactness.compactness>0.50f,
            confidence=
                (
                    pressure.confidence+
                    compactness.confidence+
                    compactness.compactness
                )/3f
        )'''
),

"CounterPressRecognitionEngine.kt":(
r'''CounterPressRecognitionResult\(\)''',
'''CounterPressRecognitionResult(
            detected=
                possession.possessed &&
                pressure.confidence>0.65f,
            confidence=
                (
                    pressure.confidence+
                    scene.fieldConfidence
                )/2f
        )'''
),

"BuildUpRecognitionEngine.kt":(
r'''BuildUpRecognitionResult\(\)''',
'''BuildUpRecognitionResult(
            detected=
                formation.detected &&
                graph.lanes.isNotEmpty(),
            confidence=
                (
                    formation.confidence+
                    teamShape.confidence+
                    graph.confidence
                )/3f
        )'''
),

"PossessionStyleRecognitionEngine.kt":(
r'''PossessionStyleRecognitionResult\(\)''',
'''PossessionStyleRecognitionResult(
            detected=
                possession.possessed &&
                graph.lanes.size>=3,
            confidence=
                (
                    possession.confidence+
                    graph.confidence+
                    pressure.confidence
                )/3f
        )'''
)

}

for fn,(old,new) in PATCHES.items():
    p=ROOT/fn
    txt=p.read_text()
    txt,n=re.subn(old,new,txt,count=1,flags=re.S)
    if n!=1:
        print("FAILED:",fn)
    else:
        p.write_text(txt)
        print("PATCHED:",fn)

PY

echo "========== VERIFY =========="
grep -RIn \
'TacticalMapResult(\|DefensiveCompactnessResult(\|WingOverloadDetectionResult(\|CentralOverloadDetectionResult(\|PressingRecognitionResult(\|CounterPressRecognitionResult(\|BuildUpRecognitionResult(\|PossessionStyleRecognitionResult(' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
