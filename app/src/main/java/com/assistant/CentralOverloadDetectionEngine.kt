package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object CentralOverloadDetectionEngine {
    fun compute(scene: SceneSnapshot): CentralOverloadDetectionResult {
        val height = com.assistant.vision.CameraProfile.captureHeight.toFloat()
        if (height <= 0f) return CentralOverloadDetectionResult()
        val center = ObservedTactics.players(scene).filter { it.y in height/3f..height*2f/3f }
        val ours = center.count { it.isUserTeam }; val theirs = center.size - ours
        return CentralOverloadDetectionResult(ObservedTactics.balance(center), ours >= 3 && ours - theirs >= 2,
            ObservedTactics.confidence(center))
    }
}

/* ========
CentralOverloadDetectionResult
======== */
