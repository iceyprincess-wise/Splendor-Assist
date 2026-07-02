#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE8_PASS_CROSS_SHOOT_DECISION_BLOCKS_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

exec >"$OUT" 2>&1

echo "===================================================================="
echo "PHASE 8 PASS/CROSS/SHOOT DECISION BLOCK AUDIT"
echo "===================================================================="
date
echo

FILES="
ActiveGestureController.kt
GameplayDecisionEngine.kt
PassingEngine.kt
CrossingEngine.kt
ShootingEngine.kt
"

for NAME in $FILES
do
    FILE=$(find adapter_smartassist/src/main/java -name "$NAME" | head -n1)
    [ -f "$FILE" ] || continue

    echo
    echo "####################################################################"
    echo "# FILE: $FILE"
    echo "####################################################################"
    echo

    echo "---------------- FULL SOURCE ----------------"
    nl -ba "$FILE"
    echo

    echo "---------------- DECISION VARIABLES ----------------"
    grep -nE \
'baseStrength|strength|decisionScore|passScore|crossScore|shotScore|passThreshold|crossThreshold|shotThreshold|mode|when \(|gestureVisionAuthority|gestureMotionAuthority|visionAuthority|adaptiveConfidence|temporalGestureConfidence|visionProximityConfidence|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|temporalMemoryState|buildUpRecognitionResult|pressingRecognitionResult|counterPressRecognitionResult|tacticalBehaviorRecognitionResult|tacticalIntelligenceResult|possessionStyleRecognitionResult|defensiveCompactnessResult|wingOverloadDetectionResult|centralOverloadDetectionResult|PassingDecision|CrossDecision|ShotDecision|PassingResult|CrossResult|ShotResult' \
"$FILE" || true
    echo

    echo "---------------- RETURN BLOCKS ----------------"
    grep -nA60 -B25 'return ' "$FILE" || true
    echo

    echo "---------------- SCORE COMPARISONS ----------------"
    grep -nE \
'>=|<=|>|<|coerceIn|coerceAtLeast|coerceAtMost|maxOf|minOf|if \(|else|when \(' \
"$FILE" || true
    echo

done

echo
echo "####################################################################"
echo "# PROJECT-WIDE THRESHOLD REFERENCES"
echo "####################################################################"
grep -RInE \
'passThreshold|crossThreshold|shotThreshold|baseStrength|passScore|crossScore|shotScore|decisionScore|strength' \
adapter_smartassist/src/main/java || true

echo
echo "####################################################################"
echo "# VISION AUTHORITY SOURCES"
echo "####################################################################"
grep -RInE \
'gestureVisionAuthority|visionAuthority|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|temporalMemoryState|buildUpRecognitionResult|pressingRecognitionResult|counterPressRecognitionResult|tacticalBehaviorRecognitionResult|tacticalIntelligenceResult|possessionStyleRecognitionResult|defensiveCompactnessResult|wingOverloadDetectionResult|centralOverloadDetectionResult' \
adapter_smartassist/src/main/java || true

echo
echo "================ END OF AUDIT ================"
