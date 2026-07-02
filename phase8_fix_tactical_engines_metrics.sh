#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

BASE=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist")

def write(name,text):
    (BASE/name).write_text(text,encoding="utf-8")

write("BuildUpRecognitionEngine.kt",'''package com.assistant.adapter.smartassist

object BuildUpRecognitionEngine {

    fun analyze(
        formation: FormationResult,
        teamShape: TeamShapeResult,
        graph: PassingLaneGraph
    ): BuildUpRecognitionResult {

        val detected = formation.found && graph.lanes.isNotEmpty()

        val confidence = (
            formation.confidence +
            teamShape.confidence +
            if (graph.lanes.isNotEmpty()) 1f else 0f
        ) / 3f

        return BuildUpRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("CounterPressRecognitionEngine.kt",'''package com.assistant.adapter.smartassist

object CounterPressRecognitionEngine {

    fun analyze(
        scene: SceneSnapshot,
        possession: BallPossessionResult,
        pressure: PressureFieldResult
    ): CounterPressRecognitionResult {

        val detected =
            possession.hasPossession &&
            possession.possessionChanged

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val confidence = (
            scene.confidence +
            possession.confidence +
            pressureFactor
        ) / 3f

        return CounterPressRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("PressingRecognitionEngine.kt",'''package com.assistant.adapter.smartassist

object PressingRecognitionEngine {

    fun analyze(
        pressure: PressureFieldResult,
        compactness: DefensiveCompactnessResult,
        formation: FormationResult
    ): PressingRecognitionResult {

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val detected =
            formation.found &&
            compactness.compactness > 0.55f

        val confidence = (
            formation.confidence +
            compactness.confidence +
            pressureFactor
        ) / 3f

        return PressingRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("PossessionStyleRecognitionEngine.kt",'''package com.assistant.adapter.smartassist

object PossessionStyleRecognitionEngine {

    fun analyze(
        possession: BallPossessionResult,
        graph: PassingLaneGraph,
        pressure: PressureFieldResult
    ): PossessionStyleRecognitionResult {

        val detected =
            possession.hasPossession &&
            possession.possessionFrames > 30L

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val laneFactor =
            if (graph.lanes.isNotEmpty()) 1f else 0f

        val confidence = (
            possession.confidence +
            laneFactor +
            pressureFactor
        ) / 3f

        return PossessionStyleRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("DefensiveCompactnessEngine.kt",'''package com.assistant.adapter.smartassist

object DefensiveCompactnessEngine {

    fun compute(
        scene: SceneSnapshot,
        defensiveLine: DefensiveLineResult,
        teamShape: TeamShapeResult
    ): DefensiveCompactnessResult {

        val confidence = (
            scene.fieldConfidence +
            defensiveLine.confidence +
            teamShape.confidence
        ) / 3f

        return DefensiveCompactnessResult(
            horizontalCompactness = teamShape.width.coerceIn(0f,1f),
            verticalCompactness = teamShape.depth.coerceIn(0f,1f),
            compactness = teamShape.compactness.coerceIn(0f,1f),
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("WingOverloadDetectionEngine.kt",'''package com.assistant.adapter.smartassist

object WingOverloadDetectionEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): WingOverloadDetectionResult {

        val overloaded = scene.playerCount >= 8

        val confidence = (
            scene.confidence +
            scene.fieldConfidence +
            if (pressure.rows>0 && occupancy.rows>0) 1f else 0f
        ) / 3f

        return WingOverloadDetectionResult(
            leftWingAdvantage = 0.5f,
            rightWingAdvantage = 0.5f,
            overloaded = overloaded,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("CentralOverloadDetectionEngine.kt",'''package com.assistant.adapter.smartassist

object CentralOverloadDetectionEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): CentralOverloadDetectionResult {

        occupancy.hashCode()
        pressure.hashCode()

        return CentralOverloadDetectionResult(
            centralControl = scene.fieldConfidence.coerceIn(0f,1f),
            overloaded = scene.playerCount >= 8,
            confidence = scene.confidence.coerceIn(0f,1f)
        )
    }
}
''')

write("TacticalMapGenerationEngine.kt",'''package com.assistant.adapter.smartassist

object TacticalMapGenerationEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult,
        teamShape: TeamShapeResult,
        defensiveLine: DefensiveLineResult,
        offensiveLine: OffensiveLineResult
    ): TacticalMapResult {

        pressure.hashCode()
        teamShape.hashCode()
        defensiveLine.hashCode()
        offensiveLine.hashCode()

        return TacticalMapResult(
            width = occupancy.columns,
            height = occupancy.rows,
            cells = FloatArray(occupancy.columns * occupancy.rows),
            confidence = scene.confidence.coerceIn(0f,1f)
        )
    }
}
''')
PY

echo "========== VERIFY =========="
grep -RIn ")/2f\\|)/3f\\|coerceIn(0f,1f)" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/{BuildUpRecognitionEngine.kt,CounterPressRecognitionEngine.kt,PressingRecognitionEngine.kt,PossessionStyleRecognitionEngine.kt,DefensiveCompactnessEngine.kt,WingOverloadDetectionEngine.kt,CentralOverloadDetectionEngine.kt,TacticalMapGenerationEngine.kt}

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin
