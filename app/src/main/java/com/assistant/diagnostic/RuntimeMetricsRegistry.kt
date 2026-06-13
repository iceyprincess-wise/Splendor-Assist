package com.assistant.diagnostic

import java.util.concurrent.atomic.AtomicLong

object RuntimeMetricsRegistry {

    val ocrDetections =
        AtomicLong(0)

    val matchDetections =
        AtomicLong(0)

    val analyticsProduced =
        AtomicLong(0)

    val trajectoryProduced =
        AtomicLong(0)

    val dvrArtifacts =
        AtomicLong(0)

    val goalkeeperTriggers =
        AtomicLong(0)

    fun snapshot(): String {

        return buildString {

            append("OCR=")
            append(ocrDetections.get())

            append(" MATCH=")
            append(matchDetections.get())

            append(" ANALYTICS=")
            append(analyticsProduced.get())

            append(" TRAJECTORY=")
            append(trajectoryProduced.get())

            append(" DVR=")
            append(dvrArtifacts.get())

            append(" GK=")
            append(goalkeeperTriggers.get())
        }
    }
}
