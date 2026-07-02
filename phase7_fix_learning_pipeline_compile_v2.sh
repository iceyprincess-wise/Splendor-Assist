#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# -------------------------------------------------
# Phase3WorldState
# -------------------------------------------------

p=pkg/"Phase3WorldState.kt"
s=p.read_text()

bad=r'''
    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),'''

s=s.replace(bad,"")

anchor='''    val tacticalIntelligenceResult: TacticalIntelligenceResult =
        TacticalIntelligenceResult()'''

replace='''    val tacticalIntelligenceResult: TacticalIntelligenceResult =
        TacticalIntelligenceResult(),

    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult()'''

if anchor in s:
    s=s.replace(anchor,replace,1)

p.write_text(s)

# -------------------------------------------------
# VisionCore
# -------------------------------------------------

v=pkg/"VisionCore.kt"
t=v.read_text()

t=t.replace(
'''PreferredPassingLaneLearningEngine.analyze(
              passingLaneGraph,
              tacticalIntelligenceResult
          )''',
'''PreferredPassingLaneLearningEngine.analyze(
              state.passingGraph,
              tacticalIntelligenceResult
          )'''
)

v.write_text(t)
PY

echo
echo "========== VERIFY =========="
grep -n "opponentBehaviourLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "playerTendencyLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "preferredPassingLaneLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "shootingHabitLearningResult" "$PKG/Phase3WorldState.kt"

grep -nA2 "PreferredPassingLaneLearningEngine.analyze" "$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
