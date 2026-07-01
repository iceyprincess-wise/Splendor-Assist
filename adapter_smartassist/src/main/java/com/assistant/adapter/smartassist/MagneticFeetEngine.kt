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
        val pressureFactor=(pressure.coerceIn(0,100)/100f)

        return MagneticFeetResult(
            touchRetention=2f+(factor*4.00f)+(pressureFactor*2.00f),
            interceptionResistance=2f+(factor*4.00f)+(pressureFactor*2.00f),
            possessionControl=2f+(factor*4.00f)+(pressureFactor*2.00f)
        )
    }
}
