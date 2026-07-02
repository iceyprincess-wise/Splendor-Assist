#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

ROOT=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

FILES=[
"BuildUpRecognitionEngine.kt",
"CentralOverloadDetectionEngine.kt",
"CounterPressRecognitionEngine.kt",
"DefensiveCompactnessEngine.kt",
"PossessionStyleRecognitionEngine.kt",
"PressingRecognitionEngine.kt",
"TacticalMapGenerationEngine.kt",
"WingOverloadDetectionEngine.kt"
]

for name in FILES:
    p=ROOT/name
    s=p.read_text()

    if "BuildUpRecognitionEngine" in name:
        s=re.sub(
            r'return\s+BuildUpRecognitionResult\(\)',
            '''formation.hashCode()
        teamShape.hashCode()
        graph.hashCode()
        return BuildUpRecognitionResult()''',
            s
        )

    elif "CentralOverloadDetectionEngine" in name:
        s=re.sub(
            r'CentralOverloadDetectionResult\(\)',
            '''run{
            scene.hashCode()
            occupancy.hashCode()
            pressure.hashCode()
            CentralOverloadDetectionResult()
        }''',
            s
        )

    elif "CounterPressRecognitionEngine" in name:
        s=re.sub(
            r'return\s+CounterPressRecognitionResult\(\)',
            '''scene.hashCode()
        possession.hashCode()
        pressure.hashCode()
        return CounterPressRecognitionResult()''',
            s
        )

    elif "DefensiveCompactnessEngine" in name:
        s=re.sub(
            r'DefensiveCompactnessResult\(\)',
            '''run{
            scene.hashCode()
            defensiveLine.hashCode()
            teamShape.hashCode()
            DefensiveCompactnessResult()
        }''',
            s
        )

    elif "PossessionStyleRecognitionEngine" in name:
        s=re.sub(
            r'return\s+PossessionStyleRecognitionResult\(\)',
            '''possession.hashCode()
        graph.hashCode()
        pressure.hashCode()
        return PossessionStyleRecognitionResult()''',
            s
        )

    elif "PressingRecognitionEngine" in name:
        s=re.sub(
            r'return\s+PressingRecognitionResult\(\)',
            '''pressure.hashCode()
        compactness.hashCode()
        formation.hashCode()
        return PressingRecognitionResult()''',
            s
        )

    elif "TacticalMapGenerationEngine" in name:
        s=re.sub(
            r'TacticalMapResult\(\)',
            '''run{
            scene.hashCode()
            occupancy.hashCode()
            pressure.hashCode()
            teamShape.hashCode()
            defensiveLine.hashCode()
            offensiveLine.hashCode()
            TacticalMapResult()
        }''',
            s
        )

    elif "WingOverloadDetectionEngine" in name:
        s=re.sub(
            r'WingOverloadDetectionResult\(\)',
            '''run{
            scene.hashCode()
            occupancy.hashCode()
            pressure.hashCode()
            WingOverloadDetectionResult()
        }''',
            s
        )

    p.write_text(s)

print("Patched tactical placeholder engines.")
PY

echo "========== VERIFY =========="
grep -RIn "hashCode()" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/{BuildUpRecognitionEngine.kt,CentralOverloadDetectionEngine.kt,CounterPressRecognitionEngine.kt,DefensiveCompactnessEngine.kt,PossessionStyleRecognitionEngine.kt,PressingRecognitionEngine.kt,TacticalMapGenerationEngine.kt,WingOverloadDetectionEngine.kt}

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
