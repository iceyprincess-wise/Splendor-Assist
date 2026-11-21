package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class DefensiveContainmentResult(
    val movements: List<Pair<Float, Float>>,
    /*
     * Queue item 9 (L2): true only while the block is actually ENGAGED
     * (ball deep on the X axis, own goal known). The inactive branch
     * still returns one hold-position movement per defender (No68-D-D20
     * shape contract), so the list alone cannot tell engaged from
     * holding — this flag is what the frame gate reads.
     */
    val active: Boolean = false
)

