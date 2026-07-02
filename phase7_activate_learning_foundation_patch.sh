#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

python3 <<'PY'
from pathlib import Path
import re
import sys

ROOT=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

targets=[]

for p in ROOT.rglob("*.kt"):
    txt=p.read_text()

    if re.search(r'\bOpponentBehaviourLearningEngine\b',txt):
        targets.append(str(p))
    if re.search(r'\bPlayerTendencyLearningEngine\b',txt):
        targets.append(str(p))
    if re.search(r'\bPreferredPassingLaneLearningEngine\b',txt):
        targets.append(str(p))
    if re.search(r'\bShootingHabitLearningEngine\b',txt):
        targets.append(str(p))

print("========== VERIFY BUILD ==========")
sys.exit(0)
PY

python3 <<'PY'
from pathlib import Path

ROOT=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

required=[
"OpponentBehaviourLearningEngine.kt",
"OpponentBehaviourLearningResult.kt",
"PlayerTendencyLearningEngine.kt",
"PlayerTendencyLearningResult.kt",
"PreferredPassingLaneLearningEngine.kt",
"PreferredPassingLaneLearningResult.kt",
"ShootingHabitLearningEngine.kt",
"ShootingHabitLearningResult.kt"
]

missing=[]

for f in required:
    if not (ROOT/f).exists():
        missing.append(f)

if missing:
    print()
    print("STOP")
    print()
    print("Missing implementation units:")
    for m in missing:
        print(" ",m)
    raise SystemExit(1)

print()
print("========== VERIFY FILES ==========")
for f in required:
    print(ROOT/f)

print()
print("========== VERIFY PIPELINE ==========")

vision=(ROOT/"VisionCore.kt").read_text()
world=(ROOT/"Phase3WorldState.kt").read_text()

checks=[
"OpponentBehaviourLearningEngine",
"PlayerTendencyLearningEngine",
"PreferredPassingLaneLearningEngine",
"ShootingHabitLearningEngine"
]

failed=False

for c in checks:
    if c not in vision:
        print("VisionCore missing:",c)
        failed=True

checks=[
"opponentBehaviourLearningResult",
"playerTendencyLearningResult",
"preferredPassingLaneLearningResult",
"shootingHabitLearningResult"
]

for c in checks:
    if c not in world:
        print("Phase3WorldState missing:",c)
        failed=True

if failed:
    raise SystemExit(1)

print("VisionCore wiring OK")
print("Phase3WorldState wiring OK")
PY

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
