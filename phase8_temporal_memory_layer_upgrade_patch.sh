#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"

python3 <<'PY'
print("""
===============================================================================
PHASE 8 PATCH PLAN (derived from audit)
===============================================================================

PATCH TARGETS

1.
Create global TemporalMemoryEngine
- rolling frame history
- fixed history window
- exponential moving averages
- observation aging
- decay
- confidence evolution
- trend estimation
- sliding statistics
- online history update

2.
Create TemporalMemoryState
- persistent runtime state
- history buffers
- EMA values
- adaptive statistics
- temporal confidence
- sample counters

3.
Wire VisionCore
- update temporal memory every processed frame
- expose temporal outputs
- feed all adaptive engines
- replace single-frame heuristic inputs with temporal inputs

4.
Upgrade all learning engines
- OpponentBehaviourLearningEngine
- PlayerTendencyLearningEngine
- PreferredPassingLaneLearningEngine
- ShootingHabitLearningEngine
- FormationAdaptationEngine
- RuntimeConfidenceCalibrationEngine
- OnlineParameterAdaptationEngine
- TacticalIntelligenceEngine

using:
- EMA
- trend
- rolling averages
- observation age
- temporal confidence
- adaptive weighting

5.
Extend Phase3WorldState
- TemporalMemoryState
- temporal confidence
- temporal trend
- history metrics
- adaptive statistics
- expose entire temporal layer

6.
Upgrade GameplayDecisionEngine
replace static weighting with:
- temporal scoring
- adaptive confidence
- historical success weighting
- temporal decision smoothing
- confidence evolution
- trend-aware arbitration
- online adaptation feedback

7.
Upgrade ActiveGestureController
consume temporal layer
consume adaptive gameplay outputs
decision strength based on:
- historical confidence
- temporal pressure
- adaptive confidence
- trend direction
- confidence evolution
- rolling decision stability

Architecture Goal

Frame
    ↓
Vision
    ↓
TemporalMemoryEngine
    ↓
Adaptive Learning Engines
    ↓
Phase3WorldState
    ↓
GameplayDecisionEngine
    ↓
ActiveGestureController

No isolated frame decisions remain.
Entire adaptive pipeline evolves continuously from accumulated gameplay history.
===============================================================================
""")
PY

echo
echo "PATCH PLAN GENERATED"
echo "Audit source:"
echo "/sdcard/SplendorAssist-Audits/PHASE8_TEMPORAL_MEMORY_LAYER_ARCHITECTURE_AUDIT.txt"
