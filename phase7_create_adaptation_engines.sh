#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

mkdir -p "$PKG"

cat > "$PKG/FormationAdaptationResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class FormationAdaptationResult(
    val confidence:Float=0f,
    val adaptationScore:Float=0f,
    val formationStable:Boolean=false
)
KOT

cat > "$PKG/RuntimeConfidenceCalibrationResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class RuntimeConfidenceCalibrationResult(
    val confidence:Float=0f,
    val calibratedConfidence:Float=0f
)
KOT

cat > "$PKG/OnlineParameterAdaptationResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class OnlineParameterAdaptationResult(
    val confidence:Float=0f,
    val adaptationGain:Float=0f
)
KOT

cat > "$PKG/FormationAdaptationEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object FormationAdaptationEngine {

    fun analyze(
        tactical:TacticalIntelligenceResult,
        opponent:OpponentBehaviourLearningResult,
        player:PlayerTendencyLearningResult
    ):FormationAdaptationResult {

        val confidence=(
            tactical.confidence+
            opponent.confidence+
            player.confidence
        )/3f

        return FormationAdaptationResult(
            confidence=confidence.coerceIn(0f,1f),
            adaptationScore=confidence.coerceIn(0f,1f),
            formationStable=confidence>=0.60f
        )
    }
}
KOT

cat > "$PKG/RuntimeConfidenceCalibrationEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object RuntimeConfidenceCalibrationEngine {

    fun analyze(
        tactical:TacticalIntelligenceResult,
        formation:FormationAdaptationResult,
        passing:PreferredPassingLaneLearningResult,
        shooting:ShootingHabitLearningResult
    ):RuntimeConfidenceCalibrationResult {

        val calibrated=(
            tactical.confidence+
            formation.confidence+
            passing.confidence+
            shooting.confidence
        )/4f

        return RuntimeConfidenceCalibrationResult(
            confidence=calibrated.coerceIn(0f,1f),
            calibratedConfidence=calibrated.coerceIn(0f,1f)
        )
    }
}
KOT

cat > "$PKG/OnlineParameterAdaptationEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object OnlineParameterAdaptationEngine {

    fun analyze(
        calibration:RuntimeConfidenceCalibrationResult,
        state:GameStateSnapshot
    ):OnlineParameterAdaptationResult {

        val gain=(
            calibration.calibratedConfidence+
            state.confidence+
            state.fieldConfidence
        )/3f

        return OnlineParameterAdaptationResult(
            confidence=gain.coerceIn(0f,1f),
            adaptationGain=gain.coerceIn(0f,1f)
        )
    }
}
KOT

echo
echo "========== VERIFY FILES =========="
find "$PKG" -maxdepth 1 \
| grep -E 'FormationAdaptation|RuntimeConfidenceCalibration|OnlineParameterAdaptation' \
| sort

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
