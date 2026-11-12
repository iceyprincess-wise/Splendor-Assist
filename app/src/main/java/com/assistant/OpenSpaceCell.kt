package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class OpenSpaceCell(
    val column:Int,
    val row:Int,
    val centerX:Float,
    val centerY:Float,
    val occupancy:Int,
    val pressure:Float,
    val openness:Float,
    val viable:Boolean
)

