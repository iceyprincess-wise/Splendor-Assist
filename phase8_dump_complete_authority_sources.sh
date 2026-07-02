#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT="$HOME/storage/shared/PHASE8_COMPLETE_AUTHORITY_SOURCE_DUMP.txt"
mkdir -p "$(dirname "$OUT")"

{
echo "======================================================================"
echo "PHASE 8 COMPLETE AUTHORITY SOURCE DUMP"
echo "======================================================================"
date
echo

FILES=(
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/SpeedCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TouchRecoveryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FrameDropCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CrossPrecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ForwardRunOpportunityEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/DefenseAuthorityEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/DecisionResult.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CompensationResult.kt
)

echo "######################################################################"
echo "# FILE EXISTENCE"
echo "######################################################################"
for f in "${FILES[@]}"; do
    if [ -f "$f" ]; then
        echo "[FOUND] $f"
    else
        echo "[MISSING] $f"
    fi
done

for f in "${FILES[@]}"; do
    [ -f "$f" ] || continue

    echo
    echo "######################################################################"
    echo "# BEGIN FILE: $f"
    echo "######################################################################"
    nl -ba "$f"
    echo
    echo "######################################################################"
    echo "# END FILE: $f"
    echo "######################################################################"
done

echo
echo "######################################################################"
echo "# GLOBAL CALL GRAPH"
echo "######################################################################"

grep -RIn --include='*.kt' \
'GameplayDecisionEngine\.decide' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'HybridResponseCompensationEngine\.compensate' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'DecisionResult' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'CompensationResult' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'\bstrength\b' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'decisionAuthority' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'adaptiveDecisionAuthority' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'adaptiveAuthority' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'responseBoost' adapter_smartassist/src/main/java || true

grep -RIn --include='*.kt' \
'priority' adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# DATA CLASSES"
echo "######################################################################"

grep -RIn -A80 -B20 \
'data class' adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# PUBLIC FUNCTIONS"
echo "######################################################################"

grep -RIn \
'^fun |^    fun |public fun |internal fun ' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "# BUILD"
echo "######################################################################"

./gradlew :adapter_smartassist:compileDebugKotlin

} > "$OUT" 2>&1

echo
echo "======================================================="
echo "SOURCE DUMP COMPLETE"
echo "$OUT"
echo "======================================================="
