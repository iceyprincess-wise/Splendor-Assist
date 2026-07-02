#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_AUTHORITY_SCORE_ORIGINS_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

exec >"$OUT" 2>&1

ROOT=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

echo "=============================================================="
echo "PHASE 8 AUTHORITY SCORE ORIGIN AUDIT"
echo "=============================================================="
date
echo

echo "=============================================================="
echo "ACTIVE GESTURE CONTROLLER"
echo "=============================================================="
AGC="$ROOT/ActiveGestureController.kt"
nl -ba "$AGC"
echo

echo "=============================================================="
echo "AUTHORITY DEFINITIONS"
echo "=============================================================="
grep -nA20 -B20 -E \
'passAuthority|shotAuthority|crossAuthority|decisionScore|passingGraphScore|shootingLaneScore|crossingLaneScore' \
"$AGC" || true
echo

echo "=============================================================="
echo "PROJECT-WIDE ORIGINS"
echo "=============================================================="
grep -RIn -A12 -B12 \
-E 'passingGraphScore|shootingLaneScore|crossingLaneScore' \
"$ROOT" || true
echo

echo "=============================================================="
echo "ASSIGNMENTS"
echo "=============================================================="
grep -RIn \
-E 'passingGraphScore *=|shootingLaneScore *=|crossingLaneScore *=' \
"$ROOT" || true
echo

echo "=============================================================="
echo "DECISION SCORE REFERENCES"
echo "=============================================================="
grep -RIn -A10 -B10 \
-E 'decisionScore' \
"$ROOT" || true
echo

echo "=============================================================="
echo "GRAPH / LANE ENGINES"
echo "=============================================================="
find "$ROOT" \
\( -name '*Passing*' -o \
   -name '*Cross*' -o \
   -name '*Shoot*' -o \
   -name '*Lane*' -o \
   -name '*Graph*' \) \
-print | while read -r f
do
    echo
    echo "############################################################"
    echo "$f"
    echo "############################################################"
    nl -ba "$f"
done

echo
echo "=============================================================="
echo "HEURISTIC COMPARISONS STILL PRESENT"
echo "=============================================================="
grep -RIn \
-E 'passScore|shotScore|crossScore|>=|<=|threshold|maxOf\(.*passAuthority|maxOf\(.*shotAuthority|maxOf\(.*crossAuthority' \
"$ROOT" || true

echo
echo "=============================================================="
echo "AUDIT COMPLETE"
echo "=============================================================="
