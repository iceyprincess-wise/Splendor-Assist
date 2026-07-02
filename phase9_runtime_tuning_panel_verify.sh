#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeTuningPanel.kt

grep -n "object RuntimeTuningPanel" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeTuningPanel.kt

grep -n "RuntimeTuningState" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeTuningPanel.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 RUNTIME TUNING PANEL VERIFIED"
