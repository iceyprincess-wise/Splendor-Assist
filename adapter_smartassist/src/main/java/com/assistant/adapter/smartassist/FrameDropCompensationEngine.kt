package com.assistant.adapter.smartassist

object FrameDropCompensationEngine {

    fun compensate(
        duration: Long,
        strength: Int
    ): Long {

        return when {

            strength >= 80 ->
                (duration * 0.55f)
                    .toLong()
                    .coerceAtLeast(4L)

            strength >= 60 ->
                (duration * 0.80f)
                    .toLong()
                    .coerceAtLeast(10L)

            strength >= 45 ->
                (duration * 0.90f)
                    .toLong()
                    .coerceAtLeast(12L)

            else ->
                duration
        }
    }
}
