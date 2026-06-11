package com.assistant

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hardware-level panic interception active app-wide
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this))

        DiagnosticsEngine.initTracking()

        initializeGoalkeeperSubsystem(this)
        bindDiagnosticsInterceptor(this)
    }
}

// Autonomous hardware registration wrapper for the Omnipotent Goalkeeper Engine
fun initializeGoalkeeperSubsystem(context: android.content.Context) {
    val hintManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        context.getSystemService(android.content.Context.PERFORMANCE_HINT_SERVICE) as? android.os.PerformanceHintManager
    } else null
    com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.initializeEngine(hintManager)
}
