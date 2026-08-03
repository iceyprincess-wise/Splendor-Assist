package com.assistant.adapter.lag

// V2 PROACTIVE
import android.content.Context
import android.hardware.display.DisplayManager
import com.assistant.diagnostic.RuntimeLogger

/**
 * Reads the REAL panel refresh rate and derives the two budgets that matter:
 * vsync budget (panel) and game budget (eFootball locked at 30fps = 33.3ms).
 * Every other lag engine grades against these instead of guessing 60Hz.
 */
object DisplayProfileEngine {

    const val GAME_FPS = 30f
    const val GAME_BUDGET_MS = 1000f / GAME_FPS   // 33.3ms

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
                "ms gameBudget=" + String.format("%.1f", GAME_BUDGET_MS) + "ms", "LAGPROF")
        } catch (_: Throwable) { }
    }
}
