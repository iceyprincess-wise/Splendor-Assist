#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT="/sdcard/SplendorAssist-Audits/PHASE8_GESTURE_STRENGTH_HEURISTICS_AUDIT.txt"
mkdir -p "$(dirname "$OUT")"

{
echo "=============================="
echo "PHASE 8 GESTURE STRENGTH AUDIT"
echo "=============================="
echo

echo "===== Gesture strength / weighting ====="
grep -RInE \
'gestureStrength|strength *=|strength=|swipeStrength|dragStrength|holdStrength|tapStrength|pressure.*strength|weightedStrength|gestureWeight|gestureConfidence' \
adapter_smartassist/src/main/java || true
echo

echo "===== Manual heuristic coefficients ====="
grep -RInE \
'0\.[0-9]+f|[1-9][0-9]*f|coerceIn|coerceAtMost|coerceAtLeast|\* *[0-9.]+f|/ *[0-9.]+f|\+ *[0-9.]+f' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt 2>/dev/null || true
echo

echo "===== Vision-derived confidence sources ====="
grep -RInE \
'temporalMemoryState|TemporalMemoryState|tacticalIntelligenceResult|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|formationAdaptationResult|tacticalBehaviorRecognitionResult|tacticalAnalyticsResult|buildUpRecognitionResult|pressingRecognitionResult|counterPressRecognitionResult|defensiveCompactnessResult|wingOverloadDetectionResult|centralOverloadDetectionResult|possessionStyleRecognitionResult|visionAuthority' \
adapter_smartassist/src/main/java || true
echo

echo "===== Gesture pipeline ====="
grep -RInE \
'ActiveGestureController|GameplayDecisionEngine|AuthorityArbitrationEngine|Gesture|executeGesture|gesture' \
adapter_smartassist/src/main/java || true
echo

echo "===== Phase3WorldState Vision outputs ====="
grep -nE 'Result\(' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt || true
echo

echo "===== Candidate heuristic strength expressions ====="
grep -RInE \
'strength.*=|confidence.*=|priority.*=|weight.*=|authority.*=' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
