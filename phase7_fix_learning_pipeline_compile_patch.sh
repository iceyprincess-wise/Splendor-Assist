#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/ "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

world=pkg/"Phase3WorldState.kt"
vision=pkg/"VisionCore.kt"
pass_engine=pkg/"PreferredPassingLaneLearningEngine.kt"

# --------------------------------------------------------
# Fix Phase3WorldState property insertion
# --------------------------------------------------------

w=world.read_text()

m=re.search(r'data\s+class\s+Phase3WorldState\s*\((.*?)\)\s*$',w,re.S)
if not m:
    raise SystemExit("Phase3WorldState primary constructor not found.")

ctor=m.group(1)

props="""

    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),
"""

for k in (
"opponentBehaviourLearningResult",
"playerTendencyLearningResult",
"preferredPassingLaneLearningResult",
"shootingHabitLearningResult"
):
    ctor=re.sub(
        rf'\n\s*val\s+{k}:[\s\S]*?(?=\n\s*val|\Z)',
        '',
        ctor,
        flags=re.S
    )

if "opponentBehaviourLearningResult" not in ctor:
    ctor=ctor.rstrip()+props

w=w[:m.start(1)]+ctor+w[m.end(1):]
world.write_text(w)

# --------------------------------------------------------
# Replace missing passingLaneGraph dependency
# --------------------------------------------------------

v=vision.read_text()

v=v.replace(
"""PreferredPassingLaneLearningEngine.analyze(
              passingLaneGraph,
              tacticalIntelligenceResult
          )""",
"""PreferredPassingLaneLearningEngine.analyze(
              tacticalIntelligenceResult
          )"""
)

vision.write_text(v)

# --------------------------------------------------------
# Match engine signature
# --------------------------------------------------------

p=pass_engine.read_text()

p=re.sub(
r'''fun\s+analyze\s*\(\s*graph\s*:\s*PassingLaneGraph\s*,\s*tactical\s*:\s*TacticalIntelligenceResult\s*\)''',
'''fun analyze(
        tactical:TacticalIntelligenceResult
    )''',
p,
flags=re.S
)

p=p.replace(
'''val laneScore=
            if(graph.lanes.isEmpty())0f
            else tactical.confidence''',
'''val laneScore=tactical.confidence'''
)

pass_engine.write_text(p)
PY

echo
echo "========== VERIFY =========="
grep -n "OpponentBehaviourLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "PreferredPassingLaneLearningEngine.analyze" "$PKG/VisionCore.kt"
grep -n "fun analyze" "$PKG/PreferredPassingLaneLearningEngine.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
