#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

F=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
OUT=/sdcard/SplendorAssist-Audits/PHASE8_LEGACY_SCORE_BLOCKS.txt
mkdir -p "$(dirname "$OUT")"

{
echo "===== shotScore ====="
grep -nA30 -B10 'shotScore' "$F" || true
echo

echo "===== passScore ====="
grep -nA30 -B10 'passScore' "$F" || true
echo

echo "===== crossScore ====="
grep -nA30 -B10 'crossScore' "$F" || true
echo

echo "===== Authorities ====="
grep -nA20 -B20 'shotAuthority\|passAuthority\|crossAuthority' "$F" || true

echo
echo "===== decide() call ====="
grep -nA20 -B10 'GameplayDecisionEngine\.decide' "$F" || true

} > "$OUT"

echo "AUDIT COMPLETE"
echo "$OUT"
