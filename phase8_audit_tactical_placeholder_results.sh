#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE8_TACTICAL_RESULT_ARCHITECTURE_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

python3 <<'PY' > "$OUT"
from pathlib import Path
import re

root=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

engines=[
"BuildUpRecognitionEngine.kt",
"CentralOverloadDetectionEngine.kt",
"CounterPressRecognitionEngine.kt",
"DefensiveCompactnessEngine.kt",
"PossessionStyleRecognitionEngine.kt",
"PressingRecognitionEngine.kt",
"TacticalMapGenerationEngine.kt",
"WingOverloadDetectionEngine.kt",
"VisionCore.kt",
"TacticalAnalysisEngine.kt"
]

print("="*110)
print("PHASE 8 TACTICAL PLACEHOLDER RESULT AUDIT")
print("="*110)

for name in engines:
    p=next(root.rglob(name),None)
    if not p:
        continue

    txt=p.read_text(errors="ignore")

    print()
    print("="*110)
    print(name)
    print("="*110)

    print("\n---- DATA / RESULT TYPES ----")
    for m in re.finditer(r"(data class|class|object)\s+\w+.*",txt):
        print(m.group(0))

    print("\n---- FUNCTION SIGNATURES ----")
    for m in re.finditer(r"fun\s+\w+\s*\([^)]*\)",txt,re.S):
        print(re.sub(r"\s+"," ",m.group(0)))

    print("\n---- RETURN OBJECTS ----")
    for m in re.finditer(r"return\s+[A-Za-z0-9_]+\([^)]*\)",txt,re.S):
        print(re.sub(r"\s+"," ",m.group(0)))

    print("\n---- INPUT TYPES ----")
    for token in [
        "SceneSnapshot",
        "PressureFieldResult",
        "FormationResult",
        "TeamShapeResult",
        "PassingLaneGraph",
        "SpaceOccupancyResult",
        "BallPossessionResult",
        "OffensiveLineResult",
        "DefensiveLineResult"
    ]:
        if token in txt:
            print(token)

    print("\n---- RESULT FIELDS ----")
    for m in re.finditer(r"Result\s*\((.*?)\)",txt,re.S):
        s=re.sub(r"\s+"," ",m.group(1)).strip()
        print(s if s else "<empty constructor>")

    print("\n---- FULL SOURCE ----")
    for i,line in enumerate(txt.splitlines(),1):
        print(f"{i:4d}: {line}")

print()
print("="*110)
print("VISIONCORE CALL SITES")
print("="*110)

vc=(root/"VisionCore.kt").read_text(errors="ignore")

for e in [
"BuildUpRecognitionEngine",
"CentralOverloadDetectionEngine",
"CounterPressRecognitionEngine",
"DefensiveCompactnessEngine",
"PossessionStyleRecognitionEngine",
"PressingRecognitionEngine",
"TacticalMapGenerationEngine",
"WingOverloadDetectionEngine"
]:
    print()
    print(e)
    for m in re.finditer(e+r".{0,300}",vc,re.S):
        print(re.sub(r"\s+"," ",m.group(0)))
PY

echo
echo "AUDIT COMPLETE"
echo "$OUT"
