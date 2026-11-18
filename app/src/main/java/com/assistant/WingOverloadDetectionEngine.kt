package com.assistant






























































































object WingOverloadDetectionEngine {
    // No68-SYNTH (warning cleanup): occupancy/pressure are intentionally
    // retained parameters (VisionCore passes them; the detector's contract
    // takes them) even though D29/D30 made the detector read positions
    // alone — hence the UNUSED_PARAMETER suppression, not a signature cut.
    @Suppress("UNUSED_PARAMETER")
    fun compute(scene: SceneSnapshot, occupancy: SpaceOccupancyResult, pressure: PressureFieldResult): WingOverloadDetectionResult {
        // No68-G-D31: fall back like FrameAssembler and
        // OverloadPlaystyleEngine instead of going blind while the camera
        // profile is still unset (captureHeight is 0 then).
        val height = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        if (height <= 0f)
            return WingOverloadDetectionResult()
        // No68-G-D29/D30: occupancy and pressure are parameters only —
        // this detector reads player positions alone. The old guard
        // returned empty when the pressure grid was malformed or the
        // occupancy grid was empty, even though neither is read.
        // (Parameters stay: VisionCore passes them.)
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
