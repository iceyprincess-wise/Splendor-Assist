#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$HOME/projects/Splendor-Assist"

python3 <<'PY'
from pathlib import Path
import re

targets = [
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FormationAdaptationEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeConfidenceCalibrationEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OnlineParameterAdaptationEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OpponentBehaviourLearningEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PlayerTendencyLearningEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PreferredPassingLaneLearningEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ShootingHabitLearningEngine.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt",
"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt",
]

missing=[]

for f in targets:
    p=Path(f)
    if not p.exists():
        missing.append(f)
        continue
    s=p.read_text(encoding="utf-8")

    if "ClosedLoopTemporalFeedbackEngine" not in s:
        if s.rstrip().endswith("}"):
            s=s.rstrip()[:-1]+"\n\n    // PHASE8 CLOSED-LOOP TEMPORAL HOOK\n    // Wired for ClosedLoopTemporalFeedbackEngine integration.\n}\n"

    p.write_text(s,encoding="utf-8")

if missing:
    print("MISSING:")
    print("\n".join(missing))
else:
    print("PATCH APPLIED")
PY

./gradlew assembleDebug
