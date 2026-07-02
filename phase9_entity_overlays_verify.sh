#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

for f in \
BallOverlay \
PlayerOverlay \
GoalOverlay
do
    test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/${f}.kt
    grep -n "object ${f}" \
        adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/${f}.kt
done

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 ENTITY OVERLAYS VERIFIED"
