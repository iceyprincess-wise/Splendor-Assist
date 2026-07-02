#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_PASS_CROSS_SHOOT_AUTHORITY_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

exec >"$OUT" 2>&1

ROOT=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

echo "======================================================================"
echo "PHASE 8 PASS / CROSS / SHOOT AUTHORITY AUDIT"
echo "======================================================================"
date
echo

echo "================ ActiveGestureController =============================="
nl -ba "$ROOT/ActiveGestureController.kt"

echo
echo "================ GameplayDecisionEngine ==============================="
nl -ba "$ROOT/GameplayDecisionEngine.kt"

echo
echo "================ Decision Inputs ======================================"
grep -RInA20 -B20 \
-E 'GameplayDecisionEngine\.decide|passAuthority|shotAuthority|crossAuthority|decisionAuthority|mode =|strength =' \
"$ROOT" || true

echo
echo "================ Legacy Score Usage ==================================="
grep -RInA10 -B10 \
-E 'shotScore|passScore|crossScore' \
"$ROOT" || true

echo
echo "================ Winner-Takes-All Logic ==============================="
grep -RIn \
-E 'maxOf|>=|<=|when *\\{|when *\\(|if *\\(|else ->|mode =' \
"$ROOT/ActiveGestureController.kt" \
"$ROOT/GameplayDecisionEngine.kt" || true

echo
echo "================ Passing Engine ======================================="
find "$ROOT" -name '*Passing*' -print | while read -r f; do
    echo
    echo "######## $f ########"
    nl -ba "$f"
done

echo
echo "================ Crossing Engine ======================================"
find "$ROOT" -name '*Cross*' -print | while read -r f; do
    echo
    echo "######## $f ########"
    nl -ba "$f"
done

echo
echo "================ Shooting Engine ======================================"
find "$ROOT" -name '*Shoot*' -print | while read -r f; do
    echo
    echo "######## $f ########"
    nl -ba "$f"
done

echo
echo "================ Vision Authority Sources ============================="
grep -RIn \
-E 'runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|temporalMemoryState|tacticalAnalyticsResult|tacticalBehaviorRecognitionResult|tacticalIntelligenceResult|buildUpRecognitionResult|pressingRecognitionResult|counterPressRecognitionResult|defensiveCompactnessResult|wingOverloadDetectionResult|centralOverloadDetectionResult' \
"$ROOT" || true

echo
echo "================ AUDIT COMPLETE ======================================="
