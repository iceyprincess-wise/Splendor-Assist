package com.assistant

import android.app.Application

import com.assistant.compliance.ComplianceMonitor
import com.assistant.recovery.AdapterRecoveryEngine
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        RuntimeLogger.initialize(this)

        AdapterHealthRegistry.initialize(
            this
        )

        ComplianceMonitor.start(this)

        AdapterRecoveryEngine.start(this)

        Thread.setDefaultUncaughtExceptionHandler(
            GlobalCrashHandler(this)
        )

        DiagnosticsEngine.initTracking()

        initializeGoalkeeperSubsystem(this)
        bindDiagnosticsInterceptor(this)
    }
}

fun initializeGoalkeeperSubsystem(
    context: android.content.Context
) {
    val hintManager =
        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {
            context.getSystemService(
                android.content.Context.PERFORMANCE_HINT_SERVICE
            ) as? android.os.PerformanceHintManager
        } else null

    com.assistant.overlay.interceptor
        .OmnipotentGoalkeeperEngine
        .initializeEngine(hintManager)
}
