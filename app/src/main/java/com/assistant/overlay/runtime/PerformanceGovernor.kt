package com.assistant.overlay.runtime

import android.content.Context

object PerformanceGovernor {

    // DVR removed (Item 3) — recording is permanently disabled.
    @Suppress("UNUSED_PARAMETER")
    fun allowRecording(
        context: Context,
        thermalLevel: Int
    ): Boolean = false
}
