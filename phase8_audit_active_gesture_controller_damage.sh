#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_ACTIVE_GESTURE_CONTROLLER_DAMAGE_AUDIT.txt
mkdir -p "$(dirname "$OUT")"

{
echo "=================================================================="
echo "ACTIVE GESTURE CONTROLLER STRUCTURAL AUDIT"
echo "=================================================================="
date
echo

FILE=adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo "================ FILE ================="
echo "$FILE"
echo

echo "================ FULL FILE (NUMBERED) ================="
nl -ba "$FILE"

echo
echo "================ REGION 80-180 ================="
sed -n '80,180p' "$FILE" | nl -ba

echo
echo "================ REGION 180-420 ================="
sed -n '180,420p' "$FILE" | nl -ba

echo
echo "================ REGION 420-760 ================="
sed -n '420,760p' "$FILE" | nl -ba

echo
echo "================ TRAJECTORY REFERENCES ================="
grep -nA6 -B6 '\<trajectory\>' "$FILE" || true

echo
echo "================ PASS/CROSS/SHOT AUTHORITY ================="
grep -nA12 -B12 'shotAuthority\|passAuthority\|crossAuthority' "$FILE" || true

echo
echo "================ DECISION AUTHORITY ================="
grep -nA12 -B12 'decisionAuthority' "$FILE" || true

echo
echo "================ GAMEPLAY DECISION CALL ================="
grep -nA20 -B10 'GameplayDecisionEngine\.decide' "$FILE" || true

echo
echo "================ BRACE BALANCE ================="
python3 - <<'PY'
from pathlib import Path

text=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt").read_text()

stack=[]
for i,ch in enumerate(text):
    if ch=="{":
        stack.append(i)
    elif ch=="}":
        if stack:
            stack.pop()
        else:
            print("EXTRA_CLOSING_BRACE@",i)

print("UNCLOSED_OPENING_BRACES =",len(stack))
for pos in stack[-20:]:
    line=text.count("\n",0,pos)+1
    print("OPEN_AT_LINE",line)
PY

echo
echo "================ KOTLIN PARSER CONTEXT ================="
./gradlew :adapter_smartassist:compileDebugKotlin --stacktrace || true

echo
echo "================ AUDIT COMPLETE ================="

} > "$OUT" 2>&1

echo
echo "AUDIT COMPLETE"
echo "$OUT"
