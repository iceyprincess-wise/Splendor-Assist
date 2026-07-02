#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

vision=pkg/"VisionCore.kt"
engine=pkg/"PreferredPassingLaneLearningEngine.kt"

v=vision.read_text()

# Discover the actual PassingLaneGraph variable already present in VisionCore.
m=re.search(r'val\s+(\w+)\s*:\s*PassingLaneGraph\s*=',v)
if not m:
    m=re.search(r'val\s+(\w+)\s*=\s*PassingLaneGraph',v)
if not m:
    m=re.search(r'val\s+(\w+)\s*=.*PassingLane.*analy',v)

if not m:
    raise SystemExit("Existing PassingLaneGraph variable not found in VisionCore.")

graph_var=m.group(1)

v=re.sub(
    r'PreferredPassingLaneLearningEngine\.analyze\(\s*.*?,\s*tacticalIntelligenceResult\s*\)',
    f'PreferredPassingLaneLearningEngine.analyze(\n              {graph_var},\n              tacticalIntelligenceResult\n          )',
    v,
    flags=re.S
)

vision.write_text(v)

e=engine.read_text()
e=re.sub(
    r'fun\s+analyze\s*\(\s*tactical\s*:\s*TacticalIntelligenceResult\s*\)',
    '''fun analyze(
        graph:PassingLaneGraph,
        tactical:TacticalIntelligenceResult
    )''',
    e,
    flags=re.S
)
e=re.sub(
    r'val\s+laneScore\s*=.*',
    '''val laneScore =
            if (graph.lanes.isEmpty()) 0f
            else tactical.confidence''',
    e
)
engine.write_text(e)

print(graph_var)
PY

echo
echo "========== VERIFY =========="
grep -nA3 "PreferredPassingLaneLearningEngine.analyze" "$PKG/VisionCore.kt"
grep -nA6 "fun analyze" "$PKG/PreferredPassingLaneLearningEngine.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
