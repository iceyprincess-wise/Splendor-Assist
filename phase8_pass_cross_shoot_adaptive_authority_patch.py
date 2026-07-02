from pathlib import Path
import re
import sys

ROOT = Path.home() / "projects" / "Splendor-Assist"

controller = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt"
decision = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt"

for f in (controller, decision):
    if not f.exists():
        print("Missing:", f)
        sys.exit(1)

ctrl = controller.read_text(encoding="utf-8")
dec = decision.read_text(encoding="utf-8")

# ---------- ActiveGestureController ----------
old = r'''        val mode =
            when {
                !hasBall -> 0
                shotAuthority >= passAuthority &&
                shotAuthority >= crossAuthority -> 2
                passAuthority >= crossAuthority -> 1
                else -> 0
            }'''

new = r'''        val adaptiveModeAuthority =
            GameplayDecisionEngine.selectVisionAdaptiveMode(
                hasBall = hasBall,
                shotAuthority = shotAuthority,
                passAuthority = passAuthority,
                crossAuthority = crossAuthority,
                visionConfidence = visionProximityConfidence,
                tacticalConfidence =
                    worldState.tacticalAnalyticsResult.confidence,
                intelligenceConfidence =
                    worldState.tacticalIntelligenceResult.confidence,
                runtimeCalibration =
                    worldState.runtimeConfidenceCalibrationResult.calibratedConfidence,
                onlineAdaptation =
                    worldState.onlineParameterAdaptationResult.adaptationGain,
                temporal =
                    worldState.temporalMemoryState
            )

        val mode = adaptiveModeAuthority.mode'''

ctrl2, n = re.subn(old, new, ctrl, count=1)
if n != 1:
    print("Unable to patch ActiveGestureController")
    sys.exit(2)

controller.write_text(ctrl2, encoding="utf-8")

# ---------- GameplayDecisionEngine ----------
if "data class AdaptiveModeAuthority" not in dec:

    insert = r'''

data class AdaptiveModeAuthority(
    val mode:Int,
    val shotScore:Float,
    val passScore:Float,
    val crossScore:Float
)

'''

    dec = dec.replace(
        "object GameplayDecisionEngine {",
        insert + "object GameplayDecisionEngine {",
        1
    )

if "fun selectVisionAdaptiveMode(" not in dec:

    hook = r'''

    fun selectVisionAdaptiveMode(
        hasBall:Boolean,
        shotAuthority:Float,
        passAuthority:Float,
        crossAuthority:Float,
        visionConfidence:Float,
        tacticalConfidence:Float,
        intelligenceConfidence:Float,
        runtimeCalibration:Float,
        onlineAdaptation:Float,
        temporal:TemporalMemoryState
    ):AdaptiveModeAuthority{

        if(!hasBall){
            return AdaptiveModeAuthority(
                0,
                shotAuthority,
                passAuthority,
                crossAuthority
            )
        }

        val temporalGain =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                runtimeCalibration +
                onlineAdaptation
            ) / 6f

        val shotScore =
            shotAuthority * 0.55f +
            intelligenceConfidence * 0.15f +
            tacticalConfidence * 0.10f +
            temporalGain * 0.10f +
            visionConfidence * 0.10f

        val passScore =
            passAuthority * 0.55f +
            tacticalConfidence * 0.15f +
            temporalGain * 0.10f +
            runtimeCalibration * 0.10f +
            visionConfidence * 0.10f

        val crossScore =
            crossAuthority * 0.55f +
            tacticalConfidence * 0.10f +
            temporalGain * 0.10f +
            onlineAdaptation * 0.15f +
            visionConfidence * 0.10f

        val mode =
            if(
                shotScore >= passScore &&
                shotScore >= crossScore
            ){
                2
            }else if(
                passScore >= crossScore
            ){
                1
            }else{
                0
            }

        return AdaptiveModeAuthority(
            mode,
            shotScore,
            passScore,
            crossScore
        )
    }

'''

    dec = dec.replace(
        "fun decide(",
        hook + "\n    fun decide(",
        1
    )

decision.write_text(dec, encoding="utf-8")

print("PATCH COMPLETE")
