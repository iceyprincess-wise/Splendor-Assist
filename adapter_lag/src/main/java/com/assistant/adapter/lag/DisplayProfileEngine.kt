package com.assistant.adapter.lag

// V3 ADMIN-WIRED
import android.content.Context
import android.hardware.display.DisplayManager
import com.assistant.diagnostic.RuntimeLogger

/**
 * Reads the REAL panel refresh rate and derives the two budgets that matter:
 * vsync budget (panel) and game budget (eFootball locked at 30fps = 33.3ms).
 * Every other lag engine grades against these instead of guessing 60Hz.
 * V3: the game frame rate is admin-tunable for the day the game changes
 * its lock - no rebuild needed (invalid/0 safely falls back to 30).
 */
object DisplayProfileEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    val gameFps: Float get() {
        val v = 30f
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
