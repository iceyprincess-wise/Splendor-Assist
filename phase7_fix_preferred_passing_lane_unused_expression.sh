#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

f=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PreferredPassingLaneLearningEngine.kt"

s=f.read_text()

# Replace the entire laneScore block with a single valid expression.
s=re.sub(
r'''val\s+laneScore\s*=\s*[\s\S]*?return\s+PreferredPassingLaneLearningResult\(''',
'''val laneScore = if (graph.lanes.isEmpty()) {
            0f
        } else {
            tactical.confidence
        }

        return PreferredPassingLaneLearningResult(''',
s,
count=1,
flags=re.S
)

f.write_text(s)
PY

echo
echo "========== VERIFY =========="
nl -ba "$PKG/PreferredPassingLaneLearningEngine.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
