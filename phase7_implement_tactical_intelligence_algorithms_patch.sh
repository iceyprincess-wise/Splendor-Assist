#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/ "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

files={}

files["TacticalAnalyticsEngine.kt"]=r'''package com.assistant.adapter.smartassist

object TacticalAnalyticsEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        tacticalMap:TacticalMapResult,
        compactness:DefensiveCompactnessResult,
        wing:WingOverloadDetectionResult,
        central:CentralOverloadDetectionResult,
        pressing:PressingRecognitionResult,
        counterPress:CounterPressRecognitionResult,
        buildUp:BuildUpRecognitionResult,
        possession:PossessionStyleRecognitionResult
    ):TacticalAnalyticsResult{

        var score=0f

        score+=tacticalMap.confidence
        score+=compactness.confidence
        score+=compactness.compactness
        score+=wing.confidence
        score+=if(wing.overloaded)0.05f else 0f
        score+=central.confidence
        score+=if(central.overloaded)0.05f else 0f
        score+=pressing.confidence
        score+=if(pressing.detected)0.05f else 0f
        score+=counterPress.confidence
        score+=if(counterPress.detected)0.05f else 0f
        score+=buildUp.confidence
        score+=if(buildUp.detected)0.05f else 0f
        score+=possession.confidence
        score+=if(possession.detected)0.05f else 0f

        val confidence=clamp(score/8.4f)

        return TacticalAnalyticsResult(
            confidence=confidence
        )
    }
}
'''

files["TacticalBehaviorRecognitionEngine.kt"]=r'''package com.assistant.adapter.smartassist

object TacticalBehaviorRecognitionEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        formation:FormationResult,
        teamShape:TeamShapeResult
    ):TacticalBehaviorRecognitionResult{

        var score=0f

        score+=analytics.confidence
        score+=formation.confidence
        score+=if(formation.found)0.20f else 0f
        score+=teamShape.confidence
        score+=teamShape.compactness

        val confidence=clamp(score/3.2f)

        return TacticalBehaviorRecognitionResult(
            confidence=confidence
        )
    }
}
'''

files["TacticalIntelligenceEngine.kt"]=r'''package com.assistant.adapter.smartassist

object TacticalIntelligenceEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        behavior:TacticalBehaviorRecognitionResult,
        state:GameStateSnapshot
    ):TacticalIntelligenceResult{

        var score=0f

        score+=analytics.confidence
        score+=behavior.confidence
        score+=state.confidence
        score+=state.fieldConfidence
        score+=if(state.ballDetected)0.10f else 0f
        score+=if(state.playerDetected)0.10f else 0f
        score+=if(state.goalDetected)0.05f else 0f
        score+=if(state.goalkeeperDetected)0.05f else 0f

        val confidence=clamp(score/3.3f)

        return TacticalIntelligenceResult(
            confidence=confidence
        )
    }
}
'''

for name,text in files.items():
    (pkg/name).write_text(text)

PY

echo
echo "========== VERIFY =========="

grep -n "score+=" "$PKG/TacticalAnalyticsEngine.kt"
grep -n "score+=" "$PKG/TacticalBehaviorRecognitionEngine.kt"
grep -n "score+=" "$PKG/TacticalIntelligenceEngine.kt"

echo
echo "========== BUILD =========="

cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
