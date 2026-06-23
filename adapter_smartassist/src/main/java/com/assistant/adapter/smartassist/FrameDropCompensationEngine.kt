package com.assistant.adapter.smartassist

object FrameDropCompensationEngine {

    fun compensate(
        duration:Long,
        strength:Int
    ):Long {

        return if(strength >= 45)
            (duration * 1.15f).toLong()
        else
            duration
    }
}
