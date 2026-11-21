package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ActiveAttackerResult(
    val found: Boolean,
    val attacker: TrackedPlayer? = null,
    val attackerIndex: Int = -1
)

// Letter A item 9: confidence deleted — written by both compute()
// overloads (and by VisionCore's defensiveAttacker) but never read by
// any consumer on the live path. The only read was the >0.30f gate
// inside the dead 5-arg direct-execute overload, which now reads
// possession.confidence directly (the identical value the result was
// built from). ActiveDefenderEngine reads found / attacker /
// attackerIndex only.

/* ========
ActiveDefenderEngine
======== */
