#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE8_GAMEPLAY_DECISION_COMPILE_FAILURE_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

FILE="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt"

{
echo "================ FILE =================="
echo "$FILE"
echo

echo "================ COMPLETE SOURCE =================="
nl -ba "$FILE"
echo

echo "================ DECIDE() SIGNATURE =================="
grep -n -A80 -B20 "fun decide" "$FILE" || true
echo

echo "================ BASE CONFIDENCE =================="
grep -n -A20 -B20 "baseConfidence" "$FILE" || true
echo

echo "================ TEMPORAL =================="
grep -n -A20 -B20 "temporal" "$FILE" || true
echo

echo "================ ADAPTIVE =================="
grep -n -A20 -B20 "adaptiveConfidence" "$FILE" || true
echo

echo "================ DECISION CONFIDENCE =================="
grep -n -A20 -B20 "temporalDecisionConfidence" "$FILE" || true
echo

echo "================ RETURN =================="
grep -n -A40 -B20 "DecisionResult" "$FILE" || true

} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"
