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
        val historyStability =
            (
                (1f - variance).coerceIn(0f,1f) * 0.50f +
                (1f - kotlin.math.abs(trend)).coerceIn(0f,1f) * 0.30f +
                ema.coerceIn(0f,1f) * 0.20f
            ).coerceIn(0f,1f)



        return TemporalMemoryState(
            historyWindow=previous.historyWindow,
            sampleCount=previous.sampleCount+1,
            rollingConfidence=mean,
            exponentialMovingAverage=ema,
            confidenceTrend=trend,
            confidenceVariance=variance,
            historyStability=historyStability,
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


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
