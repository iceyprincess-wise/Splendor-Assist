#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import subprocess
import re
import sys

FILE="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt"

def git_show(rev):
    return subprocess.check_output(
        ["git","show",f"{rev}:{FILE}"],
        text=True
    )

# Recover the last intact implementation.
base=None
for rev in ("HEAD~1","HEAD"):
    try:
        txt=git_show(rev)
        if "GameplayDecisionEngine.decide(" in txt and \
           "AuthorityArbitrationEngine.arbitrate(" in txt and \
           "val trajectory =" in txt:
            base=txt
            print("Recovered from",rev)
            break
    except Exception:
        pass

if base is None:
    raise SystemExit("FAILED: unable to recover intact controller from git")

s=base

# ------------------------------------------------------------------
# Reapply only verified Vision-first migrations that previously built.
# ------------------------------------------------------------------

s=s.replace(
    "shotScore = shotScore,\n                passScore = passScore,\n                crossScore = crossScore,\n                telemetry = telemetry",
    "shotAuthority = shotAuthority,\n                passAuthority = passAuthority,\n                crossAuthority = crossAuthority,\n                decisionAuthority = decisionAuthority,\n                telemetry = telemetry,\n                temporal = worldState.temporalMemoryState"
)

replacements=[
(
r"val shotScore\s*=\s*.*?scene\.goalConfidence",
"""val shotAuthority =
            (
                shootingLaneScore +
                worldState.tacticalIntelligenceResult.confidence +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
            ).coerceIn(0f,1f)"""
),
(
r"val passScore\s*=\s*.*?trajectory\.speed\s*\.coerceAtMost\(12f\)",
"""val passAuthority =
            (
                passingGraphScore +
                trajectory.speed.coerceIn(0f,1f) +
                worldState.buildUpRecognitionResult.confidence +
                worldState.possessionStyleRecognitionResult.confidence
            ).coerceIn(0f,1f)"""
),
(
r"val crossScore\s*=\s*.*?scene\.fieldConfidence",
"""val crossAuthority =
            (
                crossingLaneScore +
                worldState.wingOverloadDetectionResult.confidence +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
            ).coerceIn(0f,1f)"""
)
]

for pat,rep in replacements:
    s,n=re.subn(pat,rep,s,flags=re.S)
    if n!=1:
        raise SystemExit(f"FAILED replacing:\n{pat}")

mode_pat=r"""val mode\s*=\s*when\s*\{.*?else\s*->\s*0\s*\}"""
mode_rep="""val decisionAuthority =
            (
                shotAuthority +
                passAuthority +
                crossAuthority +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.temporalMemoryState.temporalConfidence
            ) / 5f

        val mode =
            when {
                !hasBall -> 0
                shotAuthority >= passAuthority &&
                shotAuthority >= crossAuthority -> 2
                passAuthority >= crossAuthority -> 1
                else -> 0
            }"""

s,n=re.subn(mode_pat,mode_rep,s,flags=re.S)
if n!=1:
    raise SystemExit("FAILED replacing mode block")

Path(FILE).write_text(s)
print("PATCHED:",FILE)
PY

echo
echo "========== VERIFY =========="
grep -n "val trajectory =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "passAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "shotAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "crossAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "decisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
