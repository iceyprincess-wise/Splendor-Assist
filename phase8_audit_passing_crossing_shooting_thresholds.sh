#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT="/sdcard/SplendorAssist-Audits/PHASE8_PASS_CROSS_SHOOT_THRESHOLD_AUDIT.txt"
mkdir -p "$(dirname "$OUT")"

{
echo "PHASE8 PASS/CROSS/SHOOT AUDIT"
echo
date
echo

ROOT=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

grep -RIn -C4 -E 'passThreshold|shotThreshold|crossThreshold|score|confidence|authority|vision|GameplayDecisionEngine|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|temporalMemoryState' "$ROOT" 2>/dev/null || true

echo
echo "================ FULL SOURCE FILES ================"

find "$ROOT" \( \
-name '*Passing*' -o \
-name '*Cross*' -o \
-name '*Shoot*' \
\) | while read -r f
do
echo
echo "########################################################"
echo "$f"
echo "########################################################"
nl -ba "$f"
done

echo
echo "================ ACTIVE GESTURE CONTROLLER ================"
nl -ba "$ROOT/ActiveGestureController.kt"

echo
echo "================ GAMEPLAY DECISION ENGINE ================"
nl -ba "$ROOT/GameplayDecisionEngine.kt"

} >"$OUT"

sync

echo
echo "AUDIT COMPLETE"

if [ -f "$OUT" ]; then
    ls -lh "$OUT"
    echo "$OUT"
else
    echo "FAILED TO CREATE AUDIT FILE"
fi
