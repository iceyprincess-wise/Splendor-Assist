package com.assistant

data class TrackedPlayer(

    val id:Int,

    var x:Float,

    var y:Float,

    var velocityX:Float,

    var velocityY:Float,

    var headingRadians:Float = 0f,

    var confidence:Float,

    val isUserTeam:Boolean,

    val isGoalkeeper:Boolean = false,

    var lastSeenFrame:Long,

    // B6: per-player velocity in px/s (PlayerMotionProducer, from the
    // measured inter-update dt). velocityX/Y above stay the EMA-smoothed
    // PER-FRAME displacement — the two spaces must never be mixed.
    // These fields sit LAST (after lastSeenFrame) on purpose: older
    // positional constructors (repo + harness) pass heading/confidence/
    // team/keeper/frame in order, and an insertion before them would
    // silently shift every positional argument.
    var velocityXPerSecond:Float = 0f,

    var velocityYPerSecond:Float = 0f

)
