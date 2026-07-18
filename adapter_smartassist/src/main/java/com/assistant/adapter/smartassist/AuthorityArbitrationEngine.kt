package com.assistant.adapter.smartassist

data class ArbitrationResult(
    val finalX: Float,
    val finalY: Float
)

object AuthorityArbitrationEngine {

    fun arbitrate(
        mode:Int,
        passX:Float,
        passY:Float,
        crossX:Float,
        crossY:Float,
        predictiveX:Float,
        predictiveY:Float,
        receiver:Float,
        forward:Float,
        recovery:Float,
        shot:Float,
        stability:Float
    ): ArbitrationResult {

        return when(mode){

            1 -> ArbitrationResult(
                passX + ((receiver * 64f) + (shot * 36f)).coerceIn(-120f,120f),
                passY + ((forward * 48f) + (stability * 8f)).coerceIn(-180f,180f)
            )

            2 -> ArbitrationResult(
                predictiveX + ((shot * 50f) + (receiver * 64f)).coerceIn(-120f,120f),
                predictiveY + ((recovery * 60f) + (stability * 8f)).coerceIn(-180f,180f)
            )

            else -> ArbitrationResult(
                crossX + (receiver * 64f).coerceIn(-120f,120f),
                crossY + ((forward * 36f) + (stability * 8f)).coerceIn(-180f,180f)
            )
        }
    }
}
