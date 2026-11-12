package com.assistant



































































object NetJitterEngine {
    @Volatile private var running = false
    // Patch 4: the polling loop is gone. The RTT value was written every
    // second and never read anywhere, and no jitter was ever computed here
    // (NetProbeEngine already computes real jitter, read by
    // ActionWindowEngine). start()/stop() stay as no-ops so
    // PerformanceEngineService keeps compiling.
    fun start() {
        running = true
    }
    fun stop() { running = false }
}

/* ========
BurstForensicsEngine
======== */
// V3 ADMIN-WIRED - every classification line answers the admin store live

/**
 * Classifies each burst and keeps a 60s picture:
 *   HICCUP      isolated burst, recovers next slice
 *   OSCILLATION several bursts inside the watch window - rhythmic micro-stutter
 *   SEIZURE     burst with a frame past the freeze line - a felt freeze
 * Publishes one state for the lag governor's FAST path (a SEIZURE here
 * escalates the rescue to HEAVY immediately). Aggregated logging only.
 *
 * V3: the freeze line, oscillation count + window, calm-restore time and
 * calm-check rhythm are all admin-tunable, re-read on every burst - no
 * restart needed. State changes publish instantly for the admin Detector.
 */
