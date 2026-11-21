package com.assistant
























import android.util.Log













import com.assistant.runtime.*
























import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*









import kotlin.math.pow


















object DefenseAuthorityEngine {

    private val evaluationCount = AtomicLong(0L)

    fun evaluate(distance: Float, strength: Int, recovery: Float, retention: Float): DefenseAuthorityResult {
        val ns = strength.coerceIn(0, 100) / 100f
        val ti = ns.pow(1.5f)
        val pf = 1f - (distance.coerceIn(0f, 1200f) / 1200f)
        val nr = recovery.coerceIn(0f, 10f) / 10f
        val nt = retention.coerceIn(0f, 10f) / 10f

        val containment = ((nr * 5.5f) + (ti * 3.5f) + (pf * 2.5f)).coerceIn(0f, 10f)
        val interception = ((nt * 5.5f) + (ti * 3.5f) + (pf * 2.5f)).coerceIn(0f, 10f)
        val pressure = (containment + interception).coerceIn(0f, 20f)

        val count = evaluationCount.incrementAndGet()

        if (count % 500L == 0L) {
            Log.d("DefenseAuthorityEngine", "containment=$containment interception=$interception pressure=$pressure")
        }

        return DefenseAuthorityResult(containment, interception, pressure)
    }
}

/* ========
DefensiveCompactnessEngine
======== */
