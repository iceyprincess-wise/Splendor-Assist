#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeDiagnosticsRegistry.kt

grep -n "object RuntimeDiagnosticsRegistry" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeDiagnosticsRegistry.kt

grep -n "enableRuntimeDiagnostics" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeDiagnosticsRegistry.kt

grep -n "disableRuntimeDiagnostics" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeDiagnosticsRegistry.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 RUNTIME DIAGNOSTICS REGISTRY VERIFIED"
