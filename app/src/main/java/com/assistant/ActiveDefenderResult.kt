package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ActiveDefenderResult(
    val found: Boolean,
    val defender: TrackedPlayer? = null,
    val distanceToAttacker: Float = Float.MAX_VALUE
)

// Letter A item 11: defenderIndex and confidence deleted — written by
// ActiveDefenderEngine.compute() but never read anywhere. The live
// readers (DefenseAuthorityContributor, FrameAssembler, VisionCore)
// readers (DefenseAuthorityContributor, FrameAssembler, VisionCore)
// read found / defender / distanceToAttacker only.
