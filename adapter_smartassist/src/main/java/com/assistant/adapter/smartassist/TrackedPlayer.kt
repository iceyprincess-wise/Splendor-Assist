package com.assistant.adapter.smartassist

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

    var lastSeenFrame:Long

)
