package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object WingOverloadDetectionEngine {
    fun compute(scene: SceneSnapshot, occupancy: SpaceOccupancyResult, pressure: PressureFieldResult): WingOverloadDetectionResult {
        val height = com.assistant.vision.CameraProfile.captureHeight.toFloat()
        if (height <= 0f || occupancy.rows <= 0 || occupancy.columns <= 0 || ObservedTactics.pressure(pressure) == null)
            return WingOverloadDetectionResult()
        val players = ObservedTactics.players(scene)
        val left = players.filter { it.y < height/3f }; val right = players.filter { it.y > height*2f/3f }
        fun overloaded(p: List<TrackedPlayer>): Boolean {
            val ours = p.count { it.isUserTeam }; return ours >= 3 && 2*ours - p.size >= 2
        }
        return WingOverloadDetectionResult(ObservedTactics.balance(left), ObservedTactics.balance(right),
            overloaded(left) || overloaded(right), ObservedTactics.confidence(left + right))
    }
}

/* ========
ZeroFramePressEngine
======== */
