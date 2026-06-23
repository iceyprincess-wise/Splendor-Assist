package com.assistant.adapter.smartassist

data class MagneticFeetResult(
    val touchRetention:Float,
    val interceptionResistance:Float,
    val possessionControl:Float
)

object MagneticFeetEngine {

    fun stabilize(
        pressure:Int,
        strength:Int
    ): MagneticFeetResult {

        val factor=(strength.coerceIn(0,100)/100f)

        return MagneticFeetResult(
            touchRetention=0.50f+(factor*0.50f),
            interceptionResistance=0.50f+(factor*0.50f),
            possessionControl=0.50f+(factor*0.50f)
        )
    }
}
