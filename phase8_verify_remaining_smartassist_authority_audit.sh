#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE8_SMARTASSIST_AUTHORITY_VERIFICATION.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

FILES="
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PassingEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/CrossingEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ShootingEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ReceiverEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ForwardRunEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/DefensiveEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PredictionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldStateStore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TacticalIntelligenceEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OnlineParameterAdaptationEngine.kt
"

{
echo "================ PHASE 8 SMARTASSIST AUTHORITY AUDIT ================"
echo "DATE: $(date)"
echo

for f in $FILES; do
    [ -f "$f" ] || continue

    echo "####################################################################"
    echo "FILE: $f"
    echo "####################################################################"
    echo

    echo "---------------- SIGNATURES ----------------"
    grep -nE '^(class|object|data class|fun )' "$f" || true
    echo

    echo "---------------- VISION / WORLD STATE ----------------"
    grep -nE 'Vision|VisionCore|Phase3WorldState|Phase3WorldStateStore|WorldState|worldState|temporalMemoryState|TemporalMemoryState|TemporalMemoryEngine|GameplayDecision|ActiveGesture|Prediction|Telemetry|Compensation' "$f" || true
    echo

    echo "---------------- HEURISTIC INDICATORS ----------------"
    grep -nE 'heuristic|threshold|fixed|magic|0\.[0-9]|1f|2f|3f|4f|5f|6f|7f|8f|9f|coerce|when|if|score|confidence|decision|authority' "$f" || true
    echo

    echo "---------------- ENGINE DEPENDENCIES ----------------"
    grep -nE 'Passing|Crossing|Shooting|Receiver|ForwardRun|Defensive|Prediction|Compensation|Gesture|Telemetry|Execution|Arbitration|Learning|Adaptation|Calibration|Formation|Opponent|Player|Tactical' "$f" || true
    echo

    echo "---------------- COMPLETE SOURCE ----------------"
    nl -ba "$f"
    echo
done

echo "==================== GIT STATUS ===================="
git status --short
echo

echo "==================== LAST COMMITS =================="
git log --oneline -10
echo

echo "==================== BUILD FILES ==================="
find adapter_smartassist/src/main/java/com/assistant/adapter/smartassist -maxdepth 1 -type f | sort

} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"
