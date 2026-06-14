package com.assistant.adapter.lmk

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager

object PerformanceHintEngine {

    fun reportActualWorkload(
        context: Context,
        actualDurationNanos: Long
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        val manager =
            context.getSystemService(
                PerformanceHintManager::class.java
            ) ?: return

        val session =
            manager.createHintSession(
                intArrayOf(android.os.Process.myTid()),
                actualDurationNanos
            ) ?: return

        session.reportActualWorkDuration(
            actualDurationNanos
        )

        session.close()
    }
}
