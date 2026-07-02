#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

LOG="$HOME/phase8_unused_build.log"
rm -f "$LOG"

echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin --rerun-tasks 2>&1 | tee "$LOG"

echo
echo "========== UNUSED WARNINGS =========="
grep -nE "warning:|Variable '.*' is never used|parameter '.*' is never used" "$LOG" || echo "NONE"

echo
echo "========== MANIFEST WARNINGS =========="
grep -n "package=\"com.assistant.adapter.smartassist\"" "$LOG" || true

echo
echo "========== LOG =========="
echo "$LOG"
