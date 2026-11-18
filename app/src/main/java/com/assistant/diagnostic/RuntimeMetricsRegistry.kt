package com.assistant.diagnostic

import java.util.concurrent.atomic.AtomicLong

object RuntimeMetricsRegistry {

    val ocrDetections =
        AtomicLong(0)

    val matchDetections =
        AtomicLong(0)

    val analyticsProduced =
        AtomicLong(0)

    val goalkeeperTriggers =
        AtomicLong(0)

    // No66-D40: there was no reset(). RuntimeCoordinator.resetRuntimeState()
    // zeroed GoalkeeperMetricsRegistry on shutdown (no64-D23 precedent) but
    // left these four counters running, so after an in-process runtime
    // restart the dashboard snapshot mixed two epochs. Called from
    // resetRuntimeState() next to the keeper reset.
    fun reset() {
        ocrDetections.set(0L)
        matchDetections.set(0L)
        analyticsProduced.set(0L)
        goalkeeperTriggers.set(0L)
    }

    fun snapshot(): String {

        return buildString {

            append("OCR=")
            append(ocrDetections.get())

            append(" MATCH=")
            append(matchDetections.get())

            append(" ANALYTICS=")
            append(analyticsProduced.get())

            append(" GK=")
            append(goalkeeperTriggers.get())
        }
    }
}
