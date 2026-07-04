package com.assistant.overlay.runtime

import android.content.Context
import android.os.BatteryManager
import com.assistant.overlay.dvr.DvrRuntimeCoordinator

object PerformanceGovernor {

    fun allowRecording(
        context: Context,
        thermalLevel: Int
    ): Boolean {

        val bm =
            context.getSystemService(
                BatteryManager::class.java
            )

        val battery =
            bm?.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            ) ?: 100

        if (!DvrRuntimeCoordinator.armed() &&
            !DvrRuntimeCoordinator.recording())
            return false

        if (battery <= 15)
            return false

        if (thermalLevel >= 4)
            return false

        return true
    }
}
