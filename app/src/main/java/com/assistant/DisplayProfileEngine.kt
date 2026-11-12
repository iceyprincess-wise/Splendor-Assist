package com.assistant







import android.content.Context



import android.hardware.display.DisplayManager
























import com.assistant.diagnostic.RuntimeLogger






























object DisplayProfileEngine {
    // Configured target, not measured game FPS.
    private const val TARGET_FPS = 60f

    // ADMIN-TUNABLE (default = original hard-coded value)
    val gameFps: Float get() {
        val v = TARGET_FPS
        return if (v > 0f) v else 30f
    }
    val gameBudgetMs: Float get() = 1000f / gameFps

    @Volatile var panelHz = 60f; private set
    @Volatile var vsyncBudgetMs = 16.67f; private set

    fun detect(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.displays.firstOrNull() ?: return
            panelHz = d.refreshRate
            vsyncBudgetMs = 1000f / panelHz
            RuntimeLogger.log("Display profile: panel=" + String.format("%.0f", panelHz) +
                "Hz vsyncBudget=" + String.format("%.1f", vsyncBudgetMs) +
                "ms gameBudget=" + String.format("%.1f", gameBudgetMs) + "ms", "LAGPROF")
        } catch (_: Throwable) { }
    }
}

// --- MERGED: FramePacingEngine.kt ---

// V3 AGGRESSIVE - mixture-aware, direct jitter

/**
 * V3: no single-cadence guessing. On an adaptive 90Hz panel running a 30fps
 * game, frames legally arrive at 1x/2x/3x vsync - the ENEMY is irregularity,
 * not any particular multiple. So we grade:
 *   jitterMs   - EWMA of |gap - prevGap| (beat-to-beat wobble, the felt stutter)
 *   stability  - share of frames in the window's dominant vsync bucket
 *   hard stalls - gap > 100ms (absolute; a real freeze at any cadence)
 */
