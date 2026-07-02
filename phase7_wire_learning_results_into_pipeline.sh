#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# ------------------------------------------------------------
# PreferredPassingLaneLearningEngine
# ------------------------------------------------------------

p=pkg/"PreferredPassingLaneLearningEngine.kt"
s=p.read_text()

s=re.sub(
r'''val laneScore =
\s*if \(graph\.lanes\.isEmpty\(\)\) 0f
\s*else tactical\.confidence''',
'''val laneScore =
            if (graph.lanes.isEmpty())
                0f
            else
                tactical.confidence''',
s,
flags=re.S
)

s=s.replace(
'''return PreferredPassingLaneLearningResult(
            confidence=tactical.confidence,
            preferredLaneScore=laneScore
        )''',
'''return PreferredPassingLaneLearningResult(
            confidence=((laneScore+tactical.confidence)/2f).coerceIn(0f,1f),
            preferredLaneScore=laneScore
        )'''
)

p.write_text(s)

# ------------------------------------------------------------
# VisionCore
# ------------------------------------------------------------

vfile=pkg/"VisionCore.kt"
v=vfile.read_text()

ctor=re.search(r'Phase3WorldState\s*\(',v)
if not ctor:
    raise SystemExit("Phase3WorldState(...) construction not found.")

insert=r'''
            opponentBehaviourLearningResult = opponentBehaviourLearningResult,
            playerTendencyLearningResult = playerTendencyLearningResult,
            preferredPassingLaneLearningResult = preferredPassingLaneLearningResult,
            shootingHabitLearningResult = shootingHabitLearningResult,
'''

start=ctor.end()
depth=1
i=start
while i < len(v):
    if v[i]=='(':
        depth+=1
    elif v[i]==')':
        depth-=1
        if depth==0:
            end=i
            break
    i+=1
else:
    raise SystemExit("Unable to locate Phase3WorldState constructor end.")

body=v[start:end]

for name in (
"opponentBehaviourLearningResult",
"playerTendencyLearningResult",
"preferredPassingLaneLearningResult",
"shootingHabitLearningResult"
):
    if name not in body:
        body=body.rstrip()+",\n"+insert if name=="opponentBehaviourLearningResult" else body

if "opponentBehaviourLearningResult =" not in body:
    body=body.rstrip()+",\n"+insert

v=v[:start]+body+v[end:]
vfile.write_text(v)
PY

echo
echo "========== VERIFY =========="
grep -n "Phase3WorldState(" "$PKG/VisionCore.kt"
grep -n "opponentBehaviourLearningResult =" "$PKG/VisionCore.kt"
grep -n "playerTendencyLearningResult =" "$PKG/VisionCore.kt"
grep -n "preferredPassingLaneLearningResult =" "$PKG/VisionCore.kt"
grep -n "shootingHabitLearningResult =" "$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
