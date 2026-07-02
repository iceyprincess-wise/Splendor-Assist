#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

cd "$ROOT"

python3 <<'PY'
from pathlib import Path
import re

f = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
text = f.read_text()

call = re.compile(
    r'GameplayDecisionEngine\.decide\s*\((.*?)\)',
    re.S
)

m = call.search(text)
if not m:
    raise SystemExit("GameplayDecisionEngine.decide(...) call not found.")

args = m.group(1)

if "worldState.temporalMemoryState" not in args:
    args = args.rstrip()
    if args.endswith(","):
        args += "\n                worldState.temporalMemoryState"
    else:
        args += ",\n                worldState.temporalMemoryState"

    text = text[:m.start(1)] + args + text[m.end(1):]
    f.write_text(text)

print("Patched.")
PY

echo "========== VERIFY =========="
grep -n "GameplayDecisionEngine.decide" \
"$PKG/ActiveGestureController.kt" -A12

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
