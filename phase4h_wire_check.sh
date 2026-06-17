#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== ACCESSIBILITY ENGINE ====="
rg -n \
'SmartAssistAccessibilityEngine' \
app/src/main/java \
--glob '!build/**'

echo
echo "===== OVERLAY ENGINE ====="
rg -n \
'OverlayService' \
app/src/main/java \
--glob '!build/**'

echo
echo "===== SURVIVAL REGISTRIES ====="
find app/src/main/java/com/assistant/survival \
-type f \
-name '*.kt'

echo
echo "PHASE4H FOUNDATION READY"
