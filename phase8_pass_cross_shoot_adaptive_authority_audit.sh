#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE8_PASS_CROSS_SHOOT_ADAPTIVE_AUTHORITY_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

FILES="
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/HybridResponseCompensationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldStateStore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TacticalIntelligenceEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OnlineParameterAdaptationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeConfidenceCalibrationEngine.kt
"

{
echo "================ PHASE8 PASS/CROSS/SHOOT ADAPTIVE AUTHORITY AUDIT ================"
echo "DATE: $(date)"
echo

echo "================ OBJECTIVE ================"
echo "Replace initial max-authority selector with Vision-derived Adaptive Authority while preserving compilation."
echo

for f in $FILES; do
    [ -f "$f" ] || continue

    echo "####################################################################"
    echo "FILE: $f"
    echo "####################################################################"
    echo

    echo "---------------- SIGNATURES ----------------"
    grep -nE '^(class|object|data class|interface|fun )' "$f" || true
    echo

    echo "---------------- MODE / DECISION ----------------"
    grep -nEi 'mode|decision|authority|priority|confidence|stable|streak|adaptive|visionAuthority|decisionAuthority|shotAuthority|passAuthority|crossAuthority|GameplayDecision|DecisionResult|when \{|when\(' "$f" || true
    echo

    echo "---------------- VISION INPUTS ----------------"
    grep -nEi 'SceneTracker|Vision|goal|goalkeeper|pressure|occupancy|field|ball|receiver|passingGraph|crossingLane|shootingLane|throughBall|runPrediction|overlap|blockedLane|interception|openSpace|telemetry' "$f" || true
    echo

    echo "---------------- WORLD STATE ----------------"
    grep -nEi 'Phase3WorldState|Phase3WorldStateStore|TemporalMemory|RuntimeConfidenceCalibration|OnlineParameterAdaptation|TacticalAnalytics|TacticalIntelligence|BuildUp|PossessionStyle|WingOverload|CounterPress|Pressing|FormationAdaptation|Learning' "$f" || true
    echo

    echo "---------------- CONTROL FLOW ----------------"
    grep -nEi 'if *\(|else|when *\{|return|coerce|firstOrNull|sortBy|maxBy|minBy|compareBy|priority|confidence' "$f" || true
    echo

    echo "---------------- COMPLETE SOURCE ----------------"
    nl -ba "$f"
    echo
done

echo "================ CROSS REFERENCES ================"
grep -RInE 'GameplayDecisionEngine\.decide|shotAuthority|passAuthority|crossAuthority|decisionAuthority|adaptiveDecisionAuthority|visionAuthority|DecisionResult|mode =' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist 2>/dev/null || true
echo

echo "================ WORLD STATE REFERENCES ================"
grep -RInE 'Phase3WorldStateStore\.current|TemporalMemoryState|RuntimeConfidenceCalibrationResult|OnlineParameterAdaptationResult|TacticalIntelligenceResult|TacticalAnalyticsResult' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist 2>/dev/null || true
echo

echo "================ COMPILATION DEPENDENCIES ================"
grep -RInE 'GameplayDecisionEngine|ActiveGestureController|DecisionResult|ExecutionRequest|HybridExecutionTerminal|AuthorityArbitrationEngine' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist 2>/dev/null || true
echo

echo "================ GIT STATUS ================"
git status --short
echo

echo "================ LAST COMMITS ================"
git log --oneline -10
echo

echo "================ SMARTASSIST FILE INDEX ================"
find adapter_smartassist/src/main/java/com/assistant/adapter/smartassist -type f | sort
echo

} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"
