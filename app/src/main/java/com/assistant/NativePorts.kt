package com.assistant

// Kotlin ports of two tiny native formulas (f17). Op-for-op mirrors of the C bodies.
// UNVERIFIED: not compiled in the build sandbox; compile happens on the user's Termux build.

object VectorSmootherPort {
    // same seed as native_vector_smoother.c: static uint32_t g_rng_state = 123456789;
    private var rngState: Int = 123456789

    private fun xorshift32(): Int {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return x
    }

    // C: (float)(xorshift32() % m - sub) in uint32 arithmetic (wraps), then unsigned->float
    private fun unsignedModMinusToFloat(x: Int, m: Int, sub: Int): Float {
        val r = Integer.remainderUnsigned(x, m)
        val d = r - sub
        return (d.toLong() and 0xFFFFFFFFL).toFloat()
    }

    fun smooth(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Float, out: FloatArray) {
        if (out.size < 33) return
        val steps = 10
        val dt = 1.0f / steps.toFloat()
        val wobbleAmp = 1.5f
        for (i in 0..steps) {
            val t = i.toFloat() * dt
            val cx = (startX + endX) * 0.5f + unsignedModMinusToFloat(xorshift32(), 20, 10)
            val cy = (startY + endY) * 0.5f + unsignedModMinusToFloat(xorshift32(), 20, 10)
            val u = 1.0f - t
            val bx = u * u * startX + 2.0f * u * t * cx + t * t * endX
            val by = u * u * startY + 2.0f * u * t * cy + t * t * endY
            val wx = unsignedModMinusToFloat(xorshift32(), 200, 100) * 0.01f * wobbleAmp
            val wy = unsignedModMinusToFloat(xorshift32(), 200, 100) * 0.01f * wobbleAmp
            val idx = i * 3
            out[idx] = bx + wx
            out[idx + 1] = by + wy
            out[idx + 2] = t * durationMs
        }
    }
}

object CounterattackPort {
    private fun branchlessCoerce(value: Float, minVal: Float, maxVal: Float): Float {
        var r = value
        r = 0.5f * (r + minVal + Math.abs(r - minVal))
        r = 0.5f * (r + maxVal - Math.abs(maxVal - r))
        return r
    }

    fun analyze(
        attackers: Int, teamShapeFound: Boolean, teamShapeConf: Float,
        offensiveLineFound: Boolean, offensiveLineConf: Float, out: FloatArray
    ) {
        if (out.size < 2) return
        var tsc = teamShapeConf
        var olc = offensiveLineConf
        if (!tsc.isFinite()) tsc = 0.0f
        if (!olc.isFinite()) olc = 0.0f
        val shapeMask = if (teamShapeFound) 1.0f else 0.0f
        val lineMask = if (offensiveLineFound) 1.0f else 0.0f
        var conf = (attackers.toFloat() * 0.090909f) + (shapeMask * 0.15f) +
            (lineMask * 0.15f) + (tsc * 0.05f) + (olc * 0.05f)
        if (!conf.isFinite()) conf = 0.0f
        conf = branchlessCoerce(conf, 0.0f, 1.0f)
        val amplified = conf * conf * (3.0f - 2.0f * conf)
        out[0] = amplified
        out[1] = conf
    }
}
