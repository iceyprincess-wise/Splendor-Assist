#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUT="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUT/PHASE8_GAMEPLAY_TEMPORAL_SOURCE_AUDIT.txt"

mkdir -p "$OUT"
: > "$OUTFILE"

cd "$ROOT"

FILES="
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldStateStore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt
"

for f in $FILES; do
    if [ -f "$f" ]; then
        {
            echo "===================================================================="
            echo "FILE: $f"
            echo "===================================================================="
            echo
            echo "----- FUNCTION SIGNATURES -----"
            grep -nE '^(data class|class |object |fun )' "$f" || true
            echo
            echo "----- TEMPORAL REFERENCES -----"
            grep -nE 'TemporalMemory|temporal|history|trend|variance|rolling|EMA|ema|confidenceEvolution|confidenceTrend|rollingMean|exponentialMovingAverage|historyStability|temporalDecisionConfidence|decision|when\\s*\\(' "$f" || true
            echo
            echo "----- FULL SOURCE -----"
            nl -ba "$f"
            echo
        } >> "$OUTFILE"
    fi
done

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"
