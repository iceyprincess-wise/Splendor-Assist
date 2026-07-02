#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

p=Path.home()/ "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PreferredPassingLaneLearningEngine.kt"

src=p.read_text()

# Determine actual lane score property instead of assuming "confidence"
m=re.search(r'class\s+\w*Lane[^{]*\{.*?val\s+(\w+)\s*:\s*Float',src,re.S)
if not m:
    m=re.search(r'data\s+class\s+\w*Lane\s*\((.*?)\)',src,re.S)

prop="score"

lane_files=list(p.parent.glob("*Passing*kt"))+list(p.parent.glob("*Lane*kt"))
for f in lane_files:
    txt=f.read_text()
    mm=re.search(r'data\s+class\s+\w*Lane\s*\((.*?)\)',txt,re.S)
    if mm:
        params=mm.group(1)
        for candidate in ("score","confidence","weight","quality","probability","value"):
            if re.search(r'\b'+candidate+r'\s*:\s*Float',params):
                prop=candidate
                break
        break

src=re.sub(
r'val laneScore=.*?return PreferredPassingLaneLearningResult\(',
f'''val laneScore =
            graph.lanes
                .map {{ it.{prop} }}
                .maxOrNull()
                ?: 0f

        return PreferredPassingLaneLearningResult(''',
src,
flags=re.S
)

p.write_text(src)
PY

echo "========== VERIFY =========="
nl -ba "$PKG/PreferredPassingLaneLearningEngine.kt" | sed -n '1,40p'

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
