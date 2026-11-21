package com.assistant






























































































object WingOverloadDetectionEngine {
    // Item 13: occupancy/pressure params REMOVED (were UNUSED_PARAMETER,
    // positions-alone by design per No68-G-D29/D30 — no weighting rule
    // exists for them, and the advantage semantics WingBlockContributor
    // relies on must not change). The detector reads player positions.
    fun compute(scene: SceneSnapshot): WingOverloadDetectionResult {
        // No68-G-D31: fall back like FrameAssembler and
        // OverloadPlaystyleEngine instead of going blind while the camera
        // profile is still unset (captureHeight is 0 then).
        val height = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        if (height <= 0f)
            return WingOverloadDetectionResult()
        // No68-G-D29/D30: this detector reads player positions alone.
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
