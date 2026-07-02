#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_DIRECT_TELEMETRY_MULTIPLIERS_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

exec >"$OUT" 2>&1

echo "============================================================"
echo "PHASE 8 : DIRECT TELEMETRY MULTIPLIER AUDIT"
echo "============================================================"
date
echo

ROOT=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

echo "============================================================"
echo "ACTIVE GESTURE CONTROLLER"
echo "============================================================"
grep -RInE 'telemetry\.[A-Za-z0-9_]+.*(\*|/|\+|-)|(\*|/|\+|-).*telemetry\.[A-Za-z0-9_]+' "$ROOT/ActiveGestureController.kt" || true
echo

echo "============================================================"
echo "GAMEPLAY DECISION ENGINE"
echo "============================================================"
grep -RInE 'telemetry\.[A-Za-z0-9_]+.*(\*|/|\+|-)|(\*|/|\+|-).*telemetry\.[A-Za-z0-9_]+' "$ROOT/GameplayDecisionEngine.kt" || true
echo

echo "============================================================"
echo "ALL TELEMETRY FIELD USAGE"
echo "============================================================"
grep -RIn "telemetry." "$ROOT" || true
echo

echo "============================================================"
echo "DIRECT NUMERIC MULTIPLIERS"
echo "============================================================"
grep -RInE '\*[[:space:]]*[0-9]+(\.[0-9]+)?f?|\*[[:space:]]*0\.[0-9]+f?' "$ROOT" || true
echo

echo "============================================================"
echo "DIRECT COEFFICIENT DIVISORS"
echo "============================================================"
grep -RInE '/[[:space:]]*[0-9]+(\.[0-9]+)?f?' "$ROOT" || true
echo

echo "============================================================"
echo "HARDCODED CONFIDENCE BLENDS"
echo "============================================================"
grep -RInE 'confidence.*0\.[0-9]|0\.[0-9].*confidence|authority.*0\.[0-9]|0\.[0-9].*authority' "$ROOT" || true
echo

echo "============================================================"
echo "ADAPTIVE SOURCES AVAILABLE"
echo "============================================================"
grep -RInE \
'RuntimeConfidenceCalibrationResult|runtimeConfidenceCalibrationResult|OnlineParameterAdaptationResult|onlineParameterAdaptationResult|TemporalMemoryState|temporalMemoryState|Adaptive|visionAuthority|gestureVisionAuthority|tacticalAnalyticsResult|tacticalIntelligenceResult|buildUpRecognitionResult|pressingRecognitionResult|counterPressRecognitionResult|possessionStyleRecognitionResult|defensiveCompactnessResult|wingOverloadDetectionResult|centralOverloadDetectionResult' \
"$ROOT" || true
echo

echo "============================================================"
echo "FIX TARGETS"
echo "============================================================"
grep -RInE \
'playerVelocity|confidence|speed|acceleration|movement|distance|telemetryBoost|decisionScore|strength|priority' \
"$ROOT" || true
echo

echo "============================================================"
echo "END OF AUDIT"
echo "============================================================"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
