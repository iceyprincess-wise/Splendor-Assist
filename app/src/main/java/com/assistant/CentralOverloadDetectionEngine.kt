package com.assistant






























































































object CentralOverloadDetectionEngine {
    fun compute(scene: SceneSnapshot): CentralOverloadDetectionResult {
        val height = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        // No68-G-D8: fall back like FrameAssembler and
        // OverloadPlaystyleEngine instead of going blind while the camera
        // profile is still unset (captureHeight is 0 then, so the old
        // raw read returned the empty result on early frames).
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
