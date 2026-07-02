#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeOverlayHub.kt

grep -n "object RuntimeOverlayHub" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeOverlayHub.kt

grep -n "enableDiagnostics" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeOverlayHub.kt

grep -n "disableDiagnostics" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeOverlayHub.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 RUNTIME OVERLAY HUB VERIFIED"
