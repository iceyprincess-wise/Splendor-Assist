#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/BoundingBoxOverlay.kt

grep -n "object BoundingBoxOverlay" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/BoundingBoxOverlay.kt

grep -n "enable()" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/BoundingBoxOverlay.kt

grep -n "disable()" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/BoundingBoxOverlay.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 BOUNDING BOX OVERLAY VERIFIED"
