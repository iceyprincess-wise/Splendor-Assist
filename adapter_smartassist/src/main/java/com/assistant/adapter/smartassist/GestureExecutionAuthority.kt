package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicLong

/*
 * The ONLY component in the gameplay domain permitted to call
 * AccessibilityService.dispatchGesture.
 *
 * Every former direct dispatcher now delegates here, which gives one place
 * to measure latency, count acceptance, log failure, and later enforce
 * cancellation policy. Callers keep their Boolean contract unchanged.
 *
 * GridRecentsInterceptor is a separate accessibility UI domain and is
 * deliberately NOT routed through this authority.
 */
object GestureExecutionAuthority {

    private val requested = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)

    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L

    fun execute(
        service: AccessibilityService,
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback? = null,
        handler: Handler? = null
    ): Boolean {
        requested.incrementAndGet()
        lastOrigin = originOfCaller()
        lastUpdatedMs = System.currentTimeMillis()

        return try {
            val result = service.dispatchGesture(gesture, callback, handler)
            if (result) accepted.incrementAndGet() else rejected.incrementAndGet()
            lastAccepted = result
            result
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastAccepted = false
            RuntimeLogger.log(
                "Gesture execution failed origin=$lastOrigin: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    private fun originOfCaller(): String =
        try {
            Thread.currentThread().stackTrace
                .firstOrNull {
                    it.className.contains("com.assistant") &&
                        !it.className.contains("GestureExecutionAuthority")
                }
                ?.className
                ?.substringAfterLast('.')
                ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }

    fun executionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "requested" to requested.get(),
        "accepted" to accepted.get(),
        "rejected" to rejected.get(),
        "failed" to failed.get(),
        "lastOrigin" to lastOrigin,
        "lastAccepted" to lastAccepted,
        "lastUpdatedMs" to lastUpdatedMs
    )

    fun reset() {
        requested.set(0L)
        accepted.set(0L)
        rejected.set(0L)
        failed.set(0L)
        lastOrigin = "none"
        lastAccepted = false
        lastUpdatedMs = 0L
    }
}
