#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

engine = Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TacticalIntelligenceEngine.kt"

t = engine.read_text()

# Replace ANY third parameter type with GameStateSnapshot
t = re.sub(
    r'(\banalyze\s*\([\s\S]*?behavior\s*:\s*TacticalBehaviorRecognitionResult\s*,\s*)(\w+)\s*:\s*[A-Za-z0-9_<>?.]+',
    r'\1state: GameStateSnapshot',
    t,
    count=1,
    flags=re.S,
)

# Replace stale symbol usage if present
t = t.replace("decisionScore", "state.decisionScore")
t = t.replace("decision.", "state.")

engine.write_text(t)
PY

echo
echo "========== VERIFY ENGINE =========="
grep -nA8 "fun analyze" "$PKG/TacticalIntelligenceEngine.kt"

echo
echo "========== VERIFY CALL =========="
grep -nA4 "TacticalIntelligenceEngine.analyze" "$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
