package com.assistant.compliance

import android.content.Context
import android.os.Handler
import android.os.Looper

import com.assistant.diagnostic.RuntimeLogger

object ComplianceMonitor {

    private val handler =
        Handler(Looper.getMainLooper())

    private var started = false

    private val runnable =
        object : Runnable {

            override fun run() {

                try {

                    RuntimeLogger.log(
                        ComplianceState.summary(contextRef!!),
                        "COMPLIANCE"
                    )

                } catch (_: Exception) {
                }

                handler.postDelayed(
                    this,
                    30000L
                )
            }
        }

    private var contextRef: Context? = null

    fun start(context: Context) {

        if (started)
            return

        started = true

        contextRef =
            context.applicationContext

        handler.post(runnable)
    }
}
