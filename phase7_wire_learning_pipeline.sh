#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

export ROOT PKG

python3 <<'PY'
from pathlib import Path
import os,re

pkg=Path(os.environ["PKG"])

vision=pkg/"VisionCore.kt"
world=pkg/"Phase3WorldState.kt"

v=vision.read_text(encoding="utf-8")
w=world.read_text(encoding="utf-8")

# ------------------------------------------------------------------
# VisionCore
# ------------------------------------------------------------------

anchor=r'(\s*val tacticalIntelligenceResult\s*=\s*TacticalIntelligenceEngine\.analyze\([\s\S]*?\)\s*)'

block=r'''

      val opponentBehaviourLearningResult =
          OpponentBehaviourLearningEngine.analyze(
              tacticalIntelligenceResult,
              state
          )

      val playerTendencyLearningResult =
          PlayerTendencyLearningEngine.analyze(
              tacticalIntelligenceResult,
              state
          )

      val preferredPassingLaneLearningResult =
          PreferredPassingLaneLearningEngine.analyze(
              passingLaneGraph,
              tacticalIntelligenceResult
          )

      val shootingHabitLearningResult =
          ShootingHabitLearningEngine.analyze(
              shootingLaneAnalysis,
              tacticalIntelligenceResult
          )

'''

v,n=re.subn(anchor,r'\1'+block,v,count=1,flags=re.S)
if n!=1:
    raise SystemExit("Unable to wire learning engines into VisionCore.")

# ------------------------------------------------------------------
# Phase3WorldState properties
# ------------------------------------------------------------------

props=r'''

    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),
'''

w,n=re.subn(
    r'(\s*val tacticalIntelligenceResult\s*:\s*TacticalIntelligenceResult\s*=\s*TacticalIntelligenceResult\(\),)',
    r'\1'+props,
    w,
    count=1,
    flags=re.S
)
if n!=1:
    raise SystemExit("Unable to extend Phase3WorldState.")

# ------------------------------------------------------------------
# Phase3WorldState construction
# ------------------------------------------------------------------

ctor=r'''

            opponentBehaviourLearningResult = opponentBehaviourLearningResult,
            playerTendencyLearningResult = playerTendencyLearningResult,
            preferredPassingLaneLearningResult = preferredPassingLaneLearningResult,
            shootingHabitLearningResult = shootingHabitLearningResult,
'''

w,n=re.subn(
    r'(\s*tacticalIntelligenceResult\s*=\s*tacticalIntelligenceResult,)',
    r'\1'+ctor,
    w,
    count=1,
    flags=re.S
)
if n!=1:
    print("NOTE: constructor assignment anchor not found; skipping assignment injection.")

vision.write_text(v,encoding="utf-8")
world.write_text(w,encoding="utf-8")
PY

echo
echo "========== VERIFY =========="
grep -n "OpponentBehaviourLearningEngine.analyze" "$PKG/VisionCore.kt"
grep -n "PlayerTendencyLearningEngine.analyze" "$PKG/VisionCore.kt"
grep -n "PreferredPassingLaneLearningEngine.analyze" "$PKG/VisionCore.kt"
grep -n "ShootingHabitLearningEngine.analyze" "$PKG/VisionCore.kt"

grep -n "opponentBehaviourLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "playerTendencyLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "preferredPassingLaneLearningResult" "$PKG/Phase3WorldState.kt"
grep -n "shootingHabitLearningResult" "$PKG/Phase3WorldState.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
