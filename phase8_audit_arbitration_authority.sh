#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_ARBITRATION_AUTHORITY_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

{
echo "======================================================================"
echo "PHASE8 ARBITRATION / AUTHORITY AUDIT"
echo "======================================================================"
date
echo

FILES=(
ArbitrationEngine.kt
GameplayDecisionEngine.kt
DecisionArbitrationEngine.kt
ExecutionAuthorityEngine.kt
VisionExecutionAuthorityEngine.kt
TelemetryAuthorityEngine.kt
VisionCore.kt
Phase3WorldState.kt
Phase3WorldStateStore.kt
ActiveGestureController.kt
HybridResponseCompensationEngine.kt
TemporalMemoryState.kt
TemporalMemoryEngine.kt
TacticalIntelligenceEngine.kt
RuntimeConfidenceCalibrationEngine.kt
OnlineParameterAdaptationEngine.kt
)

for f in "${FILES[@]}"; do
FILE="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/$f"

echo
echo "======================================================================"
echo "$FILE"
echo "======================================================================"

if [ -f "$FILE" ]; then
nl -ba "$FILE"
else
echo "MISSING"
fi
done

echo
echo "======================================================================"
echo "ALL ARBITRATION CALL SITES"
echo "======================================================================"

grep -RIn \
"Ee]ngine\\.(arbitrate|decide|select|execute|resolve|choose)" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

echo
echo "======================================================================"
echo "HEURISTIC CONSTANTS"
echo "======================================================================"

grep -RInE \
'0\\.[0-9]+f|1f|2f|3f|4f|5f|6f|7f|8f|9f|10f|20f|25f|30f|40f|50f|60f|70f|80f|90f|100f|coerceIn|coerceAtLeast|coerceAtMost|when \\(|if \\(' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/{ArbitrationEngine.kt,DecisionArbitrationEngine.kt,GameplayDecisionEngine.kt,ExecutionAuthorityEngine.kt,VisionExecutionAuthorityEngine.kt,TelemetryAuthorityEngine.kt,HybridResponseCompensationEngine.kt} 2>/dev/null || true

echo
echo "======================================================================"
echo "VISION-DERIVED AUTHORITY SOURCES"
echo "======================================================================"

grep -RInE \
'tacticalIntelligenceResult|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|temporalMemoryState|formationAdaptationResult|pressingRecognitionResult|counterPressRecognitionResult|buildUpRecognitionResult|possessionStyleRecognitionResult|tacticalMapResult|defensiveCompactnessResult|wingOverloadDetectionResult|centralOverloadDetectionResult' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

echo
echo "======================================================================"
echo "WORLD STATE AUTHORITY FIELDS"
echo "======================================================================"

grep -n "Result(" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt || true

echo
echo "======================================================================"
echo "CURRENT EXECUTION AUTHORITY PIPELINE"
echo "======================================================================"

grep -RInE \
'ExecutionAuthority|TelemetryAuthority|Arbitration|GameplayDecision|HybridResponseCompensation' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist || true

} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
