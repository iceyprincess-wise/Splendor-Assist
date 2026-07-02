#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_GAMEPLAY_DECISION_SCORE_ARBITRATION_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

exec >"$OUT" 2>&1

ROOT=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

echo "======================================================================"
echo "PHASE 8 GAMEPLAY DECISION SCORE-FIRST ARBITRATION AUDIT"
echo "======================================================================"
date
echo

echo "======================================================================"
echo "GAMEPLAY DECISION ENGINE"
echo "======================================================================"

FILE="$ROOT/GameplayDecisionEngine.kt"

if [ -f "$FILE" ]; then
    echo
    echo "FILE: $FILE"
    echo
    nl -ba "$FILE"

    echo
    echo "---------------- DECIDE() ----------------"
    grep -nA220 -B40 "fun decide" "$FILE" || true

    echo
    echo "---------------- SCORE USAGE ----------------"
    grep -nE \
'shotScore|passScore|crossScore|strength|mode|decisionScore|baseConfidence|confidence|priority|temporal|authority|adaptive|vision' \
"$FILE" || true

    echo
    echo "---------------- SCORE COMPARISONS ----------------"
    grep -nE \
'when *\\(|if *\\(|>=|<=|>|<|maxOf|minOf|coerceIn|coerceAtLeast|coerceAtMost' \
"$FILE" || true
fi

echo
echo "======================================================================"
echo "CALL SITES"
echo "======================================================================"

grep -RInA25 -B10 \
'GameplayDecisionEngine\.decide' \
"$ROOT" || true

echo
echo "======================================================================"
echo "LEGACY SCORE FLOW"
echo "======================================================================"

grep -RIn \
-E 'shotScore|passScore|crossScore' \
"$ROOT" || true

echo
echo "======================================================================"
echo "VISION / AUTHORITY FLOW"
echo "======================================================================"

grep -RIn \
-E 'decisionAuthority|gestureVisionAuthority|visionAuthority|adaptiveConfidence|temporalMemoryState|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|buildUpRecognitionResult|tacticalIntelligenceResult|pressingRecognitionResult|counterPressRecognitionResult|wingOverloadDetectionResult|centralOverloadDetectionResult|decisionScore' \
"$ROOT" || true

echo
echo "======================================================================"
echo "DECISION RESULT TYPES"
echo "======================================================================"

grep -RInA20 -B10 \
-E 'data class .*Decision|class .*Decision|DecisionResult' \
"$ROOT" || true

echo
echo "======================================================================"
echo "AUDIT COMPLETE"
echo "======================================================================"
