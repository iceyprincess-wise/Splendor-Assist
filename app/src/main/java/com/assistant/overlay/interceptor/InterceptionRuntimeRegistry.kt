package com.assistant.overlay.interceptor

object InterceptionRuntimeRegistry {

    @Volatile
    var enabled: Boolean = true

    @Volatile var autoIntercept: Boolean = true

    @Volatile var armed: Boolean = true

    @Volatile
    var awareness: Int = 100

    @Volatile
    var prediction: Int = 100
}
