#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg = Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

engine = pkg/"TacticalIntelligenceEngine.kt"
text = engine.read_text()

text = re.sub(
    r'(fun\s+analyze\s*\(\s*.*?behavior\s*:\s*TacticalBehaviorRecognitionResult\s*,\s*)DecisionResult(\s+\w+\s*\))',
    r'\1GameStateSnapshot state\2',
    text,
    flags=re.S,
)

text = re.sub(r'\bdecisionScore\b', 'state.decisionScore', text)

engine.write_text(text)

vision = pkg/"VisionCore.kt"
v = vision.read_text()

v = re.sub(
    r'TacticalIntelligenceEngine\.analyze\(\s*'
    r'tacticalAnalyticsResult\s*,\s*'
    r'tacticalBehaviorRecognitionResult\s*,\s*'
    r'state\s*'
    r'\)',
    'TacticalIntelligenceEngine.analyze(\n'
    '          tacticalAnalyticsResult,\n'
    '          tacticalBehaviorRecognitionResult,\n'
    '          state\n'
    '      )',
    v,
    flags=re.S,
)

vision.write_text(v)
PY

echo
echo "========== VERIFY =========="
grep -n "fun analyze" "$PKG/TacticalIntelligenceEngine.kt"
grep -n "GameStateSnapshot" "$PKG/TacticalIntelligenceEngine.kt"
grep -n "state.decisionScore" "$PKG/TacticalIntelligenceEngine.kt" || true
grep -n "TacticalIntelligenceEngine.analyze(" "$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin --quiet
