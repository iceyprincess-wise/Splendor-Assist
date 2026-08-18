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
 * ORIGIN ATTRIBUTION (fixed this round): previously this walked the full
 * thread stack trace (Thread.currentThread().stackTrace) on EVERY dispatch
 * to discover the caller's class name. That is a per-action array
 * allocation plus a full stack walk on the hottest path in the app -
 * pure garbage-collector pressure and dead time multiplied by every
 * single gesture, on a device that is already RAM-starved. Attribution
 * is now an explicit, zero-cost parameter with a default; callers that
 * have not yet been onboarded are counted as "unattributed" instead of
 * taxing every action to find out who they were. Passing real origins is
 * part of the per-engine onboarding pass.
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
        handler: Handler? = null,
        origin: String = "unattributed"
    ): Boolean {
        requested.incrementAndGet()
        lastOrigin = origin
        lastUpdatedMs = System.currentTimeMillis()

        return try {
            val result = service.dispatchGesture(gesture, callback, handler)
            if (result) accepted.incrementAndGet() else rejected.incrementAndGet()
            try {
                com.assistant.events.GameplayEventHub.emit(
                    if (result) "dispatch-accepted" else "dispatch-rejected",
                    "origin=$origin"
                )
            } catch (_: Throwable) {
            }
            lastAccepted = result
            result
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastAccepted = false
            RuntimeLogger.log(
                "Gesture execution failed origin=$origin: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
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
