#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT="$HOME/storage/shared/PHASE8_FINAL_INTERFACE_AUTHORITY_AUDIT.txt"
mkdir -p "$(dirname "$OUT")"

{
echo "======================================================================"
echo "PHASE 8 FINAL INTERFACE AUTHORITY AUDIT"
echo "======================================================================"
date
echo

echo "######################################################################"
echo "# 1. ALL strength PRODUCERS / CONSUMERS"
echo "######################################################################"
grep -RIn --include='*.kt' '\bstrength\b' adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 2. HybridResponseCompensationEngine"
echo "######################################################################"
grep -RIn -A80 -B30 \
'fun compensate' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt || true

echo
echo "######################################################################"
echo "# 3. ALL compensate(...) CALL SITES"
echo "######################################################################"
grep -RIn \
'HybridResponseCompensationEngine\.compensate' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 4. GameplayDecisionEngine.decide"
echo "######################################################################"
grep -RIn -A80 -B30 \
'fun decide' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt || true

echo
echo "######################################################################"
echo "# 5. ALL decide(...) CALL SITES"
echo "######################################################################"
grep -RIn \
'GameplayDecisionEngine\.decide' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 6. DecisionResult"
echo "######################################################################"
grep -RIn -A60 -B20 \
'data class DecisionResult' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 7. CompensationResult"
echo "######################################################################"
grep -RIn -A60 -B20 \
'data class CompensationResult' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 8. decisionAuthority / adaptiveDecisionAuthority FLOW"
echo "######################################################################"
grep -RIn \
'decisionAuthority\|adaptiveDecisionAuthority\|adaptiveAuthority' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 9. RuntimeConfidenceCalibrationResult FLOW"
echo "######################################################################"
grep -RIn \
'runtimeConfidenceCalibrationResult' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 10. OnlineParameterAdaptationResult FLOW"
echo "######################################################################"
grep -RIn \
'onlineParameterAdaptationResult' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 11. TemporalMemoryState FLOW"
echo "######################################################################"
grep -RIn \
'temporalMemoryState' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 12. ALL PUBLIC SIGNATURES USING strength"
echo "######################################################################"
grep -RIn \
'fun .*strength.*Int\|strength: Int\|strength:Int' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# 13. BUILD"
echo "######################################################################"
./gradlew :adapter_smartassist:compileDebugKotlin

} > "$OUT" 2>&1

echo
echo "AUDIT COMPLETE"
echo "$OUT"
