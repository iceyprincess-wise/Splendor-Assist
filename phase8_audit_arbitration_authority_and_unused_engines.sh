#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE8_ARBITRATION_AUTHORITY_UNUSED_ENGINE_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

{
echo "======================================================================"
echo "PHASE 8 : ARBITRATION AUTHORITY + UNUSED ENGINE FULL AUDIT"
echo "======================================================================"
date
echo

echo "================ GameplayDecisionEngine =============================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt 2>/dev/null
echo

echo "================ ActiveGestureController ============================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt 2>/dev/null
echo

echo "================ HybridResponseCompensationEngine ===================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt 2>/dev/null
echo

echo "================ Arbitration ========================================="
find adapter_smartassist/src/main/java/com/assistant/adapter/smartassist \
-type f \( -iname "*Arbitration*.kt" -o -iname "*Authority*.kt" \) \
-print0 | while IFS= read -r -d '' f; do
echo
echo "FILE: $f"
nl -ba "$f"
done
echo

echo "================ VisionCore =========================================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt 2>/dev/null
echo

echo "================ Phase3WorldState ===================================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt 2>/dev/null
echo

echo "================ TemporalMemoryEngine ================================"
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt 2>/dev/null
echo

echo "================ TemporalMemoryState ================================"
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryState.kt 2>/dev/null
echo

echo "================ Remaining hard-coded weights ========================"
grep -RInE \
'0\.[0-9]+f|[1-9][0-9]*f|coerceIn|coerceAtMost|coerceAtLeast|\*[[:space:]]*[0-9]+f|/[[:space:]]*[0-9]+f' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist \
2>/dev/null
echo

echo "================ Unused parameters ==================================="
grep -RInE \
'warning: parameter|fun .*\\(' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist \
2>/dev/null
echo

echo "================ BuildUpRecognitionEngine ============================"
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/BuildUpRecognitionEngine.kt 2>/dev/null
echo

echo "================ CentralOverloadDetectionEngine ======================"
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CentralOverloadDetectionEngine.kt 2>/dev/null
echo

echo "================ CounterPressRecognitionEngine ======================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CounterPressRecognitionEngine.kt 2>/dev/null
echo

echo "================ DefensiveCompactnessEngine =========================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/DefensiveCompactnessEngine.kt 2>/dev/null
echo

echo "================ PossessionStyleRecognitionEngine ===================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PossessionStyleRecognitionEngine.kt 2>/dev/null
echo

echo "================ PressingRecognitionEngine ==========================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PressingRecognitionEngine.kt 2>/dev/null
echo

echo "================ TacticalMapGenerationEngine ========================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TacticalMapGenerationEngine.kt 2>/dev/null
echo

echo "================ WingOverloadDetectionEngine ========================="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/WingOverloadDetectionEngine.kt 2>/dev/null
echo

echo "================ Cross references ===================================="
grep -RIn \
-E 'GameplayDecisionEngine|HybridResponseCompensationEngine|Authority|Arbitration|TemporalMemoryState|temporalMemoryState' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist \
2>/dev/null
echo

echo "================ END ================================================"
} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
