#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE8_UNUSED_ENGINE_INPUT_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

FILES="
BuildUpRecognitionEngine.kt
CentralOverloadDetectionEngine.kt
CounterPressRecognitionEngine.kt
DefensiveCompactnessEngine.kt
PossessionStyleRecognitionEngine.kt
PressingRecognitionEngine.kt
TacticalMapGenerationEngine.kt
WingOverloadDetectionEngine.kt
VisionCore.kt
"

{
echo "======================================================================"
echo "PHASE 8 UNUSED ENGINE INPUT AUDIT"
echo "======================================================================"
date
echo

for f in $FILES; do
FILE=$(find adapter_smartassist/src/main/java -name "$f" | head -n1)
[ -z "$FILE" ] && continue

echo
echo "######################################################################"
echo "FILE: $FILE"
echo "######################################################################"
echo

echo "----- FULL SOURCE -----"
nl -ba "$FILE"
echo

echo "----- FUNCTION SIGNATURES -----"
grep -nE 'fun |object |class ' "$FILE" || true
echo

echo "----- RETURN STATEMENTS -----"
grep -n 'return ' "$FILE" || true
echo

echo "----- PARAMETERS -----"
python3 - "$FILE" <<'PY'
import re,sys
txt=open(sys.argv[1]).read()
for m in re.finditer(r'fun\s+\w+\s*\((.*?)\)',txt,re.S):
    print(m.group(1))
    print("------------------------------------------------")
PY
echo

echo "----- FIELD ACCESS -----"
grep -nE '\.[A-Za-z_][A-Za-z0-9_]*' "$FILE" || true
echo
done

echo
echo "######################################################################"
echo "VISIONCORE CALL SITES"
echo "######################################################################"
grep -RInE \
'BuildUpRecognitionEngine|CentralOverloadDetectionEngine|CounterPressRecognitionEngine|DefensiveCompactnessEngine|PossessionStyleRecognitionEngine|PressingRecognitionEngine|TacticalMapGenerationEngine|WingOverloadDetectionEngine' \
adapter_smartassist/src/main/java || true

echo
echo "######################################################################"
echo "CURRENT UNUSED PARAMETER WARNINGS"
echo "######################################################################"
./gradlew :adapter_smartassist:compileDebugKotlin --rerun-tasks 2>&1 | \
grep -n "warning: parameter" || true

echo
echo "######################################################################"
echo "END OF AUDIT"
echo "######################################################################"

} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
