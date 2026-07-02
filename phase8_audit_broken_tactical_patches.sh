#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_BROKEN_TACTICAL_PATCH_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

{
echo "====================================================================="
echo "PHASE8 BROKEN TACTICAL PATCH AUDIT"
echo "====================================================================="
date
echo

for f in \
BuildUpRecognitionEngine.kt \
CounterPressRecognitionEngine.kt \
DefensiveCompactnessEngine.kt \
PressingRecognitionEngine.kt \
PossessionStyleRecognitionEngine.kt \
WingOverloadDetectionEngine.kt \
CentralOverloadDetectionEngine.kt \
TacticalMapGenerationEngine.kt \
BuildUpRecognitionResult.kt \
CounterPressRecognitionResult.kt \
DefensiveCompactnessResult.kt \
PressingRecognitionResult.kt \
PossessionStyleRecognitionResult.kt \
WingOverloadDetectionResult.kt \
CentralOverloadDetectionResult.kt \
TacticalMapResult.kt
do
FILE="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/$f"

echo
echo "====================================================================="
echo "$FILE"
echo "====================================================================="

if [ -f "$FILE" ]; then
nl -ba "$FILE"
else
echo "MISSING"
fi
done

echo
echo "====================================================================="
echo "ACTUAL MODEL APIs"
echo "====================================================================="

for f in \
SceneSnapshot.kt \
FormationResult.kt \
TeamShapeResult.kt \
PressureFieldResult.kt \
SpaceOccupancyResult.kt \
PassingLaneGraph.kt \
BallPossessionResult.kt \
DefensiveLineResult.kt \
OffensiveLineResult.kt
do
FILE="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/$f"

echo
echo "---------------------------------------------------------------------"
echo "$FILE"
echo "---------------------------------------------------------------------"

[ -f "$FILE" ] && nl -ba "$FILE"
done

echo
echo "====================================================================="
echo "VISION CORE CALL SITES"
echo "====================================================================="

grep -RIn \
'BuildUpRecognitionEngine\.analyze\|CounterPressRecognitionEngine\.analyze\|DefensiveCompactnessEngine\.compute\|PressingRecognitionEngine\.analyze\|PossessionStyleRecognitionEngine\.analyze\|WingOverloadDetectionEngine\.compute\|CentralOverloadDetectionEngine\.compute\|TacticalMapGenerationEngine\.compute' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

echo
echo "====================================================================="
echo "CONSTRUCTORS CURRENTLY USED"
echo "====================================================================="

grep -RIn \
'BuildUpRecognitionResult(\|CounterPressRecognitionResult(\|DefensiveCompactnessResult(\|PressingRecognitionResult(\|PossessionStyleRecognitionResult(\|WingOverloadDetectionResult(\|CentralOverloadDetectionResult(\|TacticalMapResult(' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

echo
echo "====================================================================="
echo "BUILD ERRORS LIKELY CAUSED BY PATCH"
echo "====================================================================="

grep -RIn \
'confidence=|detected=|horizontalCompactness=|verticalCompactness=|centralControl=|leftWingAdvantage=|rightWingAdvantage=|cells=|width=|height=' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
