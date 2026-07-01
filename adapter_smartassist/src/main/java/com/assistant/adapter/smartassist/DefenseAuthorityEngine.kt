package com.assistant.adapter.smartassist

data class DefenseAuthorityResult(
    val containment: Float,
    val interception: Float,
    val pressure: Float
)

object DefenseAuthorityEngine {

    fun evaluate(
        distance: Float,
        strength: Int,
        recovery: Float,
        retention: Float
    ): DefenseAuthorityResult {

        val intensity=(strength.coerceIn(0,100)/100f)
        val distanceFactor=(distance.coerceIn(0f,1000f)/1000f)

        val containment =
            ((recovery*9.0f)+(intensity*6.5f)+(distanceFactor*5.5f))

        val interception =
            ((retention*9.0f)+(intensity*6.5f)+(distanceFactor*5.5f))

        val pressure =
            containment +
            interception

        return DefenseAuthorityResult(
            containment,
            interception,
            pressure
        )
    }
}
