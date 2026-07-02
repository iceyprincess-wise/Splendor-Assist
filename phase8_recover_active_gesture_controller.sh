#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_ACTIVE_GESTURE_CONTROLLER_RECOVERY.txt
mkdir -p "$(dirname "$OUT")"

{
echo "================================================================"
echo "ACTIVEGESTURECONTROLLER RECOVERY AUDIT"
echo "================================================================"
date
echo

echo "================ GIT STATUS ================"
git status --short 2>/dev/null || true
echo

echo "================ GIT LOG ================"
git log --oneline -- adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt 2>/dev/null || true
echo

echo "================ GIT FILE HISTORY ================"
git log --follow --name-status -- adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt 2>/dev/null || true
echo

echo "================ GIT SHOW (LATEST PREVIOUS) ================"
git show HEAD~1:adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt 2>/dev/null || true
echo

echo "================ BACKUP FILES ================"
find . \
-type f \
\( \
-name "ActiveGestureController*.kt" -o \
-name "*.orig" -o \
-name "*.bak" -o \
-name "*.backup" -o \
-name "*ActiveGesture*" \
\) 2>/dev/null
echo

echo "================ RIPGREP DUPLICATES ================"
grep -Rns "class ActiveGestureController" . 2>/dev/null || true
echo

echo "================ PLACEHOLDER DETECTION ================"
grep -n "placeholder reached" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
echo

echo "================ CURRENT FILE LENGTH ================"
wc -l adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
echo

echo "================ CURRENT FILE SHA256 ================"
sha256sum adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
echo

echo "================ END ================"

} > "$OUT" 2>&1

echo "AUDIT COMPLETE"
echo "$OUT"
