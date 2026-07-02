#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_REMAINING_HEURISTIC_PIPELINE_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

{
echo "=================================================================="
echo "PHASE 8 REMAINING HEURISTIC PIPELINE AUDIT"
echo "=================================================================="
date
echo

FILES=(
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
)

for f in "${FILES[@]}"; do
    echo
    echo "##################################################################"
    echo "FILE: $f"
    echo "##################################################################"

    [ -f "$f" ] || { echo "MISSING"; continue; }

    echo
    echo "----- baseStrength -----"
    grep -nA20 -B10 "baseStrength" "$f" || true

    echo
    echo "----- passThreshold -----"
    grep -nA10 -B10 "passThreshold" "$f" || true

    echo
    echo "----- shotThreshold -----"
    grep -nA10 -B10 "shotThreshold" "$f" || true

    echo
    echo "----- crossThreshold -----"
    grep -nA10 -B10 "crossThreshold" "$f" || true

    echo
    echo "----- decisionScore -----"
    grep -nA25 -B15 "decisionScore" "$f" || true

    echo
    echo "----- strength -----"
    grep -nA35 -B25 "val strength" "$f" || true

    echo
    echo "----- telemetryBoost -----"
    grep -nA20 -B20 "telemetryBoost" "$f" || true

    echo
    echo "----- priority -----"
    grep -nA30 -B20 "priority" "$f" || true

    echo
    echo "----- responseBoost -----"
    grep -nA25 -B20 "responseBoost" "$f" || true

    echo
    echo "----- playerVelocity -----"
    grep -nA10 -B10 "playerVelocity" "$f" || true

    echo
    echo "----- telemetry.confidence -----"
    grep -nA10 -B10 "telemetry.confidence" "$f" || true

    echo
    echo "----- decisionAuthority -----"
    grep -nA20 -B20 "decisionAuthority" "$f" || true

    echo
    echo "----- shotAuthority -----"
    grep -nA10 -B10 "shotAuthority" "$f" || true

    echo
    echo "----- passAuthority -----"
    grep -nA10 -B10 "passAuthority" "$f" || true

    echo
    echo "----- crossAuthority -----"
    grep -nA10 -B10 "crossAuthority" "$f" || true

    echo
    echo "----- runtimeConfidenceCalibrationResult -----"
    grep -nA10 -B10 "runtimeConfidenceCalibrationResult" "$f" || true

    echo
    echo "----- temporalMemoryState -----"
    grep -nA10 -B10 "temporalMemoryState" "$f" || true

    echo
    echo "----- onlineParameterAdaptationResult -----"
    grep -nA10 -B10 "onlineParameterAdaptationResult" "$f" || true

    echo
    echo "----- tacticalAnalyticsResult -----"
    grep -nA10 -B10 "tacticalAnalyticsResult" "$f" || true
done

echo
echo "=================================================================="
echo "BUILD VERIFICATION"
echo "=================================================================="
./gradlew :adapter_smartassist:compileDebugKotlin

} > "$OUT" 2>&1

echo
echo "AUDIT COMPLETE"
echo "$OUT"
