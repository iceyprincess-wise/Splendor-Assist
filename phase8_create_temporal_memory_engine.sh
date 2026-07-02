#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

mkdir -p "$PKG"

cat > "$PKG/TemporalMemoryState.kt" <<'KOT'
package com.assistant.adapter.smartassist

data class TemporalMemoryState(
    val historyWindow:Int=30,
    val sampleCount:Int=0,
    val rollingConfidence:Float=0f,
    val exponentialMovingAverage:Float=0f,
    val confidenceTrend:Float=0f,
    val confidenceVariance:Float=0f,
    val confidenceSlope:Float=0f,
    val confidenceEvolution:Float=0f,
    val observationAge:Int=0,
    val decayFactor:Float=0.98f,
    val minConfidence:Float=0f,
    val maxConfidence:Float=0f,
    val temporalConfidence:Float=0f,
    val rollingMean:Float=0f,
    val rollingStdDev:Float=0f,
    val onlineUpdateCount:Int=0,
    val history:List<Float> = emptyList()
)
KOT

cat > "$PKG/TemporalMemoryEngine.kt" <<'KOT'
package com.assistant.adapter.smartassist

import kotlin.math.sqrt

object TemporalMemoryEngine{

    private const val DEFAULT_WINDOW=30
    private const val DEFAULT_ALPHA=0.20f

    fun update(
        previous:TemporalMemoryState,
        confidence:Float
    ):TemporalMemoryState{

        val history=
            (previous.history+confidence)
                .takeLast(previous.historyWindow)

        val samples=history.size

        val mean=
            if(samples==0)0f
            else history.sum()/samples.toFloat()

        val variance=
            if(samples==0)0f
            else history.fold(0f){a,v->
                val d=v-mean
                a+d*d
            }/samples.toFloat()

        val stddev=sqrt(variance)

        val ema=
            if(previous.sampleCount==0)
                confidence
            else
                (DEFAULT_ALPHA*confidence)+
                ((1f-DEFAULT_ALPHA)*previous.exponentialMovingAverage)

        val trend=
            ema-previous.exponentialMovingAverage

        val evolution=
            ema-previous.temporalConfidence

        val age=
            previous.observationAge+1

        val decayed=
            ema*previous.decayFactor

        return TemporalMemoryState(
            historyWindow=previous.historyWindow,
            sampleCount=previous.sampleCount+1,
            rollingConfidence=mean,
            exponentialMovingAverage=ema,
            confidenceTrend=trend,
            confidenceVariance=variance,
            confidenceSlope=trend,
            confidenceEvolution=evolution,
            observationAge=age,
            decayFactor=previous.decayFactor,
            minConfidence=history.minOrNull()?:confidence,
            maxConfidence=history.maxOrNull()?:confidence,
            temporalConfidence=decayed.coerceIn(0f,1f),
            rollingMean=mean,
            rollingStdDev=stddev,
            onlineUpdateCount=previous.onlineUpdateCount+1,
            history=history
        )
    }

    fun initialize(
        historyWindow:Int=DEFAULT_WINDOW
    )=TemporalMemoryState(
        historyWindow=historyWindow
    )
}
KOT

echo
echo "========== VERIFY FILES =========="
find "$PKG" -maxdepth 1 \
-name "TemporalMemoryEngine.kt" \
-o -name "TemporalMemoryState.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
