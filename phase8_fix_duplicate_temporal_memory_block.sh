#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
VC="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt"

python3 <<'PY'
from pathlib import Path
import re

p = Path.home() / "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt"
text = p.read_text()

pattern = re.compile(
    r'''
[ \t]*val\ previousTemporalMemory\s*=\s*
[ \t]*Phase3WorldStateStore\.current\(\)\.temporalMemoryState\s*

[ \t]*val\ temporalMemoryState\s*=\s*
[ \t]*TemporalMemoryEngine\.update\(
.*?
[ \t]*\)
''',
    re.S | re.X
)

matches = list(pattern.finditer(text))

if len(matches) > 1:
    first = matches[0]
    rebuilt = text[:first.end()]
    last = first.end()
    for m in matches[1:]:
        rebuilt += text[last:m.start()]
        last = m.end()
    rebuilt += text[last:]
    text = rebuilt

p.write_text(text)
PY

echo "========== VERIFY =========="
grep -n "val previousTemporalMemory" "$VC"
grep -n "val temporalMemoryState" "$VC"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
