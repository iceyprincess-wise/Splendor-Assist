#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

grep -RIn "PHASE9_RUNTIME_ACTIVATION_MARKER" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

git status --short

./gradlew assembleDebug

echo
echo "PATCH VERIFIED"
