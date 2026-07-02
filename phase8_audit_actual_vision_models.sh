#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE8_ACTUAL_VISION_MODEL_API_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

python3 <<'PY' > "$OUT"
from pathlib import Path
import re

ROOT=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

TYPES=[
"SceneSnapshot",
"FormationResult",
"TeamShapeResult",
"PressureFieldResult",
"SpaceOccupancyResult",
"PassingLaneGraph",
"BallPossessionResult",
"DefensiveLineResult",
"OffensiveLineResult"
]

print("="*120)
print("ACTUAL VISION MODEL API AUDIT")
print("="*120)

for t in TYPES:
    found=False
    for p in ROOT.rglob("*.kt"):
        txt=p.read_text(errors="ignore")
        m=re.search(r"(data\s+class|class)\s+"+re.escape(t)+r"\b.*?(?=\n(?:data\s+class|class|object)\s|\Z)",txt,re.S)
        if m:
            found=True
            print("\n"+"="*120)
            print(t)
            print("FILE:",p)
            print("="*120)
            lines=txt.splitlines()
            for i,l in enumerate(lines,1):
                print(f"{i:5d}: {l}")
            break
    if not found:
        print("\nMISSING:",t)

print("\n"+"="*120)
print("TACTICAL ENGINE CALL SITES")
print("="*120)

for name in [
"BuildUpRecognitionEngine",
"CentralOverloadDetectionEngine",
"CounterPressRecognitionEngine",
"DefensiveCompactnessEngine",
"PossessionStyleRecognitionEngine",
"PressingRecognitionEngine",
"TacticalMapGenerationEngine",
"WingOverloadDetectionEngine"
]:
    for p in ROOT.rglob("VisionCore.kt"):
        txt=p.read_text(errors="ignore")
        for m in re.finditer(name+r".{0,500}",txt,re.S):
            print("\n---",name,"---")
            print(re.sub(r"\s+"," ",m.group(0)))
PY

echo
echo "AUDIT COMPLETE"
echo "$OUT"
