#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUT="$HOME/storage/shared/PHASE8_FINAL_COMPLETE_REFACTOR_DUMP.txt"

mkdir -p "$(dirname "$OUT")"

exec >"$OUT" 2>&1

echo "======================================================================"
echo "PHASE 8 FINAL COMPLETE REFACTOR DUMP"
echo "======================================================================"
date
echo

echo "###############################"
echo "# PROJECT FILE LIST"
echo "###############################"
find "$ROOT" -type f \( -name "*.kt" -o -name "*.java" \) | sort

echo
echo "###############################"
echo "# ALL AUTHORITY REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" \
-e "Authority" \
-e "authority" \
-e "adaptiveDecisionAuthority" \
-e "decisionAuthority" \
-e "shotAuthority" \
-e "passAuthority" \
-e "crossAuthority" \
"$ROOT" || true

echo
echo "###############################"
echo "# ALL STRENGTH REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" '\bstrength\b' "$ROOT" || true

echo
echo "###############################"
echo "# ALL PRIORITY REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" '\bpriority\b' "$ROOT" || true

echo
echo "###############################"
echo "# ALL CONFIDENCE REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" \
-e "confidence" \
-e "adaptiveConfidence" \
"$ROOT" || true

echo
echo "###############################"
echo "# ALL RESPONSE BOOST REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" \
-e "responseBoost" \
-e "predictiveFactor" \
-e "compensate(" \
"$ROOT" || true

echo
echo "###############################"
echo "# DecisionResult REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" 'DecisionResult' "$ROOT" || true

echo
echo "###############################"
echo "# CompensationResult REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" 'CompensationResult' "$ROOT" || true

echo
echo "###############################"
echo "# GameplayDecisionEngine REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" \
-e 'GameplayDecisionEngine' \
-e 'decide(' \
"$ROOT" || true

echo
echo "###############################"
echo "# HybridResponseCompensationEngine REFERENCES"
echo "###############################"
grep -RIn --include="*.kt" \
-e 'HybridResponseCompensationEngine' \
-e 'compensate(' \
"$ROOT" || true

echo
echo "###############################"
echo "# COMPLETE SOURCE FILES"
echo "###############################"

FILES="
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/DecisionResult.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CompensationResult.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/SpeedCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TouchRecoveryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FrameDropCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CrossPrecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ForwardRunOpportunityEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/DefenseAuthorityEngine.kt
"

for rel in $FILES; do
    f="$ROOT/$rel"
    if [ -f "$f" ]; then
        echo
        echo "######################################################################"
        echo "# BEGIN FILE: $rel"
        echo "######################################################################"
        nl -ba "$f"
        echo
        echo "######################################################################"
        echo "# END FILE: $rel"
        echo "######################################################################"
    else
        echo
        echo "[MISSING] $rel"
    fi
done

echo
echo "###############################"
echo "# BUILD"
echo "###############################"
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

echo
echo "======================================================================"
echo "DUMP COMPLETE"
echo "======================================================================"
