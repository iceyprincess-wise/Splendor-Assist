#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

mkdir -p "$ROOT"

cat > "$ROOT/OpponentBehaviourLearningResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class OpponentBehaviourLearningResult(
    val confidence:Float=0f,
    val aggression:Float=0f,
    val pressFrequency:Float=0f,
    val transitionSpeed:Float=0f
)
KOT

cat > "$ROOT/PlayerTendencyLearningResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class PlayerTendencyLearningResult(
    val confidence:Float=0f,
    val passBias:Float=0f,
    val dribbleBias:Float=0f,
    val shootBias:Float=0f
)
KOT

cat > "$ROOT/PreferredPassingLaneLearningResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class PreferredPassingLaneLearningResult(
    val confidence:Float=0f,
    val preferredLaneScore:Float=0f
)
KOT

cat > "$ROOT/ShootingHabitLearningResult.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class ShootingHabitLearningResult(
    val confidence:Float=0f,
    val longShotBias:Float=0f,
    val boxShotBias:Float=0f
)
KOT

cat > "$ROOT/OpponentBehaviourLearningEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object OpponentBehaviourLearningEngine{

    fun analyze(
        tactical:TacticalIntelligenceResult,
        state:GameStateSnapshot
    ):OpponentBehaviourLearningResult{

        val confidence=((tactical.confidence+state.confidence)/2f).coerceIn(0f,1f)

        return OpponentBehaviourLearningResult(
            confidence=confidence,
            aggression=confidence,
            pressFrequency=confidence*0.9f,
            transitionSpeed=confidence*0.8f
        )
    }
}
KOT

cat > "$ROOT/PlayerTendencyLearningEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object PlayerTendencyLearningEngine{

    fun analyze(
        tactical:TacticalIntelligenceResult,
        state:GameStateSnapshot
    ):PlayerTendencyLearningResult{

        val confidence=((tactical.confidence+state.confidence)/2f).coerceIn(0f,1f)

        return PlayerTendencyLearningResult(
            confidence=confidence,
            passBias=confidence*0.8f,
            dribbleBias=confidence*0.7f,
            shootBias=confidence*0.6f
        )
    }
}
KOT

cat > "$ROOT/PreferredPassingLaneLearningEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object PreferredPassingLaneLearningEngine{

    fun analyze(
        graph:PassingLaneGraph,
        tactical:TacticalIntelligenceResult
    ):PreferredPassingLaneLearningResult{

        val laneScore=
            if(graph.lanes.isEmpty())0f
            else tactical.confidence

        return PreferredPassingLaneLearningResult(
            confidence=tactical.confidence,
            preferredLaneScore=laneScore
        )
    }
}
KOT

cat > "$ROOT/ShootingHabitLearningEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

object ShootingHabitLearningEngine{

    fun analyze(
        shooting:ShootingLaneAnalysis,
        tactical:TacticalIntelligenceResult
    ):ShootingHabitLearningResult{

        val confidence=tactical.confidence

        return ShootingHabitLearningResult(
            confidence=confidence,
            longShotBias=confidence*0.5f,
            boxShotBias=
                if(shooting.lanes.isEmpty())
                    confidence*0.3f
                else
                    confidence
        )
    }
}
KOT

echo
echo "========== VERIFY FILES =========="
find "$ROOT" -maxdepth 1 \
| grep -E 'OpponentBehaviourLearning|PlayerTendencyLearning|PreferredPassingLaneLearning|ShootingHabitLearning' \
| sort

echo
echo "========== BUILD =========="
cd "$HOME/projects/Splendor-Assist"
./gradlew :adapter_smartassist:compileDebugKotlin
