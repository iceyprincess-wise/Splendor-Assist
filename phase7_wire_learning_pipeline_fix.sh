#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

export ROOT PKG

python3 <<'PY'
from pathlib import Path
import os,re,sys

pkg=Path(os.environ["PKG"])

vision=pkg/"VisionCore.kt"
world=pkg/"Phase3WorldState.kt"

v=vision.read_text(encoding="utf-8")
w=world.read_text(encoding="utf-8")

# ------------------------------------------------------------
# Wire VisionCore
# ------------------------------------------------------------

if "OpponentBehaviourLearningEngine.analyze(" not in v:

    m=re.search(
        r'(\s*val\s+tacticalIntelligenceResult\s*=\s*TacticalIntelligenceEngine\.analyze\([\s\S]*?\)\s*)',
        v,
        re.S
    )

    if not m:
        sys.exit("VisionCore tactical intelligence block not found.")

    inject=r'''

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

    v=v[:m.end()]+inject+v[m.end():]

vision.write_text(v,encoding="utf-8")

# ------------------------------------------------------------
# Extend Phase3WorldState before final ')'
# ------------------------------------------------------------

if "opponentBehaviourLearningResult:" not in w:

    idx=w.rfind(")")
    if idx==-1:
        sys.exit("Phase3WorldState closing ')' not found.")

    block=r'''

    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),'''

    w=w[:idx]+block+w[idx:]

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
